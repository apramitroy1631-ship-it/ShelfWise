package com.shelfwise.core.outbox;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;

import com.shelfwise.core.config.AppSettings;

import io.r2dbc.spi.Readable;
import reactor.core.publisher.Mono;

/**
 * The CURRENT situation behind a grouped notification (D7), computed when the relay publishes a marker.
 * Because each message carries the whole current count (not "+1"), the notification service can apply
 * messages twice or late and still show the right number (it keeps the newest {@code asOf}).
 */
@Component
public class StateSnapshots {

    private final DatabaseClient db;
    private final AppSettings settings;

    public StateSnapshots(DatabaseClient db, AppSettings settings) {
        this.db = db;
        this.settings = settings;
    }

    /** Out of stock (0) and running low (at or below the reorder level) in one store; archived products don't count (D3). */
    public Mono<Map<String, Object>> stockHealth(String storeId) {
        return db.sql("""
                        WITH items AS (
                            SELECT p.name, i.updated_at, i.sku, stock_bucket(i.quantity, i.reorder_level) AS bucket
                            FROM inventory i JOIN products p ON p.sku = i.sku
                            WHERE i.store_id = :store AND p.status = 'ACTIVE'
                        )
                        SELECT clock_timestamp() AS as_of,
                               count(*) FILTER (WHERE bucket = 'OUT') AS out_count,
                               count(*) FILTER (WHERE bucket = 'LOW') AS low_count,
                               (array_agg(name ORDER BY updated_at DESC, sku) FILTER (WHERE bucket = 'OUT'))[1] AS out_latest,
                               (array_agg(name ORDER BY updated_at DESC, sku) FILTER (WHERE bucket = 'LOW'))[1] AS low_latest
                        FROM items
                        """)
                .bind("store", storeId)
                .map((row, meta) -> {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("storeId", storeId);
                    payload.put("outOfStock", group(row, "out_count", "out_latest", null));
                    payload.put("runningLow", group(row, "low_count", "low_latest", null));
                    payload.put("asOf", row.get("as_of", OffsetDateTime.class).toString());
                    return payload;
                })
                .one();
    }

    /**
     * POs waiting for finance: submitted ones, plus imported ones flagged for re-approval (D17-D19).
     * "Needs a second approval" = already has one valid approval and is above the remote-config limit.
     */
    public Mono<Map<String, Object>> approvalQueue() {
        return settings.integer("purchaseOrders.secondApprovalAboveInr", 50_000).flatMap(limit -> db.sql("""
                        WITH waiting AS (
                            SELECT po.id, coalesce(po.submitted_at, po.created_at) AS since,
                                   (SELECT count(*) FROM po_approvals a WHERE a.po_id = po.id AND a.counts AND a.step IS NOT NULL) >= 1
                                       AND po.total_amount > :limit AS second
                            FROM purchase_orders po
                            WHERE po.status = 'SUBMITTED'
                               OR (po.requires_reapproval AND po.status IN ('APPROVED', 'PARTIALLY_RECEIVED'))
                        )
                        SELECT clock_timestamp() AS as_of,
                               count(*) FILTER (WHERE NOT second) AS first_count,
                               min(since) FILTER (WHERE NOT second) AS first_oldest,
                               (array_agg(id ORDER BY since, id) FILTER (WHERE NOT second))[1] AS first_po,
                               count(*) FILTER (WHERE second) AS second_count,
                               min(since) FILTER (WHERE second) AS second_oldest,
                               (array_agg(id ORDER BY since, id) FILTER (WHERE second))[1] AS second_po
                        FROM waiting
                        """)
                .bind("limit", limit)
                .map((row, meta) -> {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("needsApproval", group(row, "first_count", "first_po", "first_oldest"));
                    payload.put("needsSecondApproval", group(row, "second_count", "second_po", "second_oldest"));
                    payload.put("asOf", row.get("as_of", OffsetDateTime.class).toString());
                    return payload;
                })
                .one());
    }

    private static Map<String, Object> group(Readable row, String countColumn, String latestColumn, String oldestColumn) {
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("count", row.get(countColumn, Long.class));
        group.put("latest", row.get(latestColumn, String.class));
        if (oldestColumn != null) {
            OffsetDateTime oldest = row.get(oldestColumn, OffsetDateTime.class);
            group.put("oldestSince", oldest == null ? null : oldest.toString());
        }
        return group;
    }
}
