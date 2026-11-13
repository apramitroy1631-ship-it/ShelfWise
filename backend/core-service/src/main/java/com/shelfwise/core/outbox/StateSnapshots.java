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

    /**
     * Stuck work in one store: open orders past the order limit (D32) and approved POs still waiting for goods past
     * the PO limit (D22), both from remote config. "Latest" is the longest-waiting one, so the notification names it.
     */
    public Mono<Map<String, Object>> stuckWork(String storeId) {
        return Mono.zip(settings.integer("orders.stuckAfterDays", 7), settings.integer("purchaseOrders.stuckAfterDays", 30))
                .flatMap(limits -> db.sql("""
                                WITH o AS (
                                    SELECT id, created_at AS since FROM customer_orders
                                    WHERE store_id = :store AND status IN ('PENDING', 'PACKED', 'SHIPPED')
                                      AND created_at < now() - make_interval(days => :orderDays)
                                ), p AS (
                                    SELECT id, since FROM (
                                        SELECT po.id, coalesce((SELECT max(a.decided_at) FROM po_approvals a
                                                                WHERE a.po_id = po.id AND a.counts AND a.step IS NOT NULL), po.created_at) AS since
                                        FROM purchase_orders po
                                        WHERE po.store_id = :store AND po.status IN ('APPROVED', 'PARTIALLY_RECEIVED')
                                    ) waiting
                                    WHERE since < now() - make_interval(days => :poDays)
                                )
                                SELECT clock_timestamp() AS as_of,
                                       (SELECT count(*) FROM o) AS o_count, (SELECT min(since) FROM o) AS o_oldest,
                                       (SELECT id FROM o ORDER BY since, id LIMIT 1) AS o_first,
                                       (SELECT count(*) FROM p) AS p_count, (SELECT min(since) FROM p) AS p_oldest,
                                       (SELECT id FROM p ORDER BY since, id LIMIT 1) AS p_first
                                """)
                        .bind("store", storeId).bind("orderDays", limits.getT1()).bind("poDays", limits.getT2())
                        .map((row, meta) -> {
                            Map<String, Object> payload = new LinkedHashMap<>();
                            payload.put("storeId", storeId);
                            payload.put("stuckOrders", group(row, "o_count", "o_first", "o_oldest"));
                            payload.put("stuckPurchaseOrders", group(row, "p_count", "p_first", "p_oldest"));
                            payload.put("asOf", row.get("as_of", OffsetDateTime.class).toString());
                            return payload;
                        })
                        .one());
    }

    /**
     * What head office sees (D7): imported orders waiting for review (D10) and stock health across all stores.
     * For stock, "latest" carries how many stores are affected ("190 items out of stock across 5 stores").
     */
    public Mono<Map<String, Object>> headOffice() {
        return db.sql("""
                        WITH r AS (
                            SELECT id, created_at FROM customer_orders WHERE review_status = 'NEEDS_REVIEW'
                        ), items AS (
                            SELECT i.store_id, stock_bucket(i.quantity, i.reorder_level) AS bucket
                            FROM inventory i JOIN products p ON p.sku = i.sku
                            WHERE p.status = 'ACTIVE'
                        )
                        SELECT clock_timestamp() AS as_of,
                               (SELECT count(*) FROM r) AS r_count, (SELECT min(created_at) FROM r) AS r_oldest,
                               (SELECT id FROM r ORDER BY created_at, id LIMIT 1) AS r_first,
                               count(*) FILTER (WHERE bucket = 'OUT') AS out_count,
                               (count(DISTINCT store_id) FILTER (WHERE bucket = 'OUT'))::text AS out_stores,
                               count(*) FILTER (WHERE bucket = 'LOW') AS low_count,
                               (count(DISTINCT store_id) FILTER (WHERE bucket = 'LOW'))::text AS low_stores
                        FROM items
                        """)
                .map((row, meta) -> {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("needsReview", group(row, "r_count", "r_first", "r_oldest"));
                    payload.put("outOfStock", group(row, "out_count", "out_stores", null));
                    payload.put("runningLow", group(row, "low_count", "low_stores", null));
                    payload.put("asOf", row.get("as_of", OffsetDateTime.class).toString());
                    return payload;
                })
                .one();
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
