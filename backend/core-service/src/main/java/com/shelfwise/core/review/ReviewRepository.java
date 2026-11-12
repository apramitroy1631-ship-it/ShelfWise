package com.shelfwise.core.review;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Queries and atomic updates behind the review queue. */
@Repository
public class ReviewRepository {

    static final String WAITING = "NEEDS_REVIEW";

    public record OrderRow(String id, String storeId, String status, OffsetDateTime createdAt, String customerName,
                           BigDecimal totalAmount, String reviewStatus, String reviewNote, String reviewedBy,
                           String reviewedByName, OffsetDateTime reviewedAt, long version) {
    }

    public record LineRow(String orderId, String sku, String productSku, String productName, int qty, BigDecimal unitPrice,
                          BigDecimal lineTotal, String priceSource) {
    }

    private static final String ORDER_COLUMNS = """
            o.id, o.store_id, o.status, o.created_at, o.customer_name, o.total_amount, o.review_status, o.review_note,
            o.reviewed_by, u.name AS reviewed_by_name, o.reviewed_at, o.version
            FROM customer_orders o LEFT JOIN users u ON u.id = o.reviewed_by
            """;

    private final DatabaseClient db;

    public ReviewRepository(DatabaseClient db) {
        this.db = db;
    }

    /** Waiting orders oldest first (the longest wait is handled first); decided ones newest decision first. */
    public Flux<OrderRow> list(boolean waiting, String storeId) {
        String where = waiting ? "o.review_status = 'NEEDS_REVIEW'" : "o.review_status IN ('CORRECTED', 'REJECTED')";
        String order = waiting ? "o.created_at, o.id" : "o.reviewed_at DESC, o.id";
        DatabaseClient.GenericExecuteSpec spec = db.sql("SELECT " + ORDER_COLUMNS + " WHERE " + where
                + (storeId == null ? "" : " AND o.store_id = :store") + " ORDER BY " + order + " LIMIT 500");
        if (storeId != null) {
            spec = spec.bind("store", storeId);
        }
        return spec.map((row, meta) -> toOrder(row)).all();
    }

    public Mono<OrderRow> find(String id) {
        return db.sql("SELECT " + ORDER_COLUMNS + " WHERE o.id = :id AND o.review_status IS NOT NULL")
                .bind("id", id).map((row, meta) -> toOrder(row)).one();
    }

    public Mono<Long> waitingCount(String storeId) {
        DatabaseClient.GenericExecuteSpec spec = db.sql("SELECT count(*) FROM customer_orders WHERE review_status = 'NEEDS_REVIEW'"
                + (storeId == null ? "" : " AND store_id = :store"));
        if (storeId != null) {
            spec = spec.bind("store", storeId);
        }
        return spec.map((row, meta) -> row.get(0, Long.class)).one();
    }

    public Flux<LineRow> lines(String[] orderIds) {
        return db.sql("""
                        SELECT l.order_id, l.sku_text, l.product_sku, p.name, l.qty, l.unit_price, l.line_total, l.price_source
                        FROM order_lines l LEFT JOIN products p ON p.sku = l.product_sku
                        WHERE l.order_id = ANY(:ids) ORDER BY l.order_id, l.id
                        """)
                .bind("ids", orderIds)
                .map((row, meta) -> new LineRow(row.get("order_id", String.class), row.get("sku_text", String.class),
                        row.get("product_sku", String.class), row.get("name", String.class), row.get("qty", Integer.class),
                        row.get("unit_price", BigDecimal.class), row.get("line_total", BigDecimal.class),
                        row.get("price_source", String.class)))
                .all();
    }

    /** The "before" snapshot saved in the audit log when an order was corrected (JSON text), per order. */
    public Flux<String[]> originals(String[] orderIds) {
        return db.sql("""
                        SELECT DISTINCT ON (entity_id) entity_id, before_value::text AS before
                        FROM audit_log
                        WHERE entity_type = 'ORDER' AND action = 'ORDER_REVIEW_CORRECTED' AND entity_id = ANY(:ids)
                        ORDER BY entity_id, created_at
                        """)
                .bind("ids", orderIds)
                .map((row, meta) -> new String[] {row.get("entity_id", String.class), row.get("before", String.class)})
                .all();
    }

    /**
     * Records the decision, but only if the order still waits AND nobody changed it since the admin loaded it.
     * Two admins deciding at once: exactly one of these updates matches; the other gets 0 rows (D10).
     */
    public Mono<Boolean> decide(String id, long version, String outcome, String note, String userId) {
        return db.sql("""
                        UPDATE customer_orders
                        SET review_status = :outcome, review_note = :note, reviewed_by = :user, reviewed_at = now(),
                            updated_at = now(), version = version + 1
                        WHERE id = :id AND version = :version AND review_status = 'NEEDS_REVIEW'
                        """)
                .bind("outcome", outcome).bind("note", note).bind("user", userId).bind("id", id).bind("version", version)
                .fetch().rowsUpdated().map(n -> n == 1);
    }

    /** Sets a flagged line's quantity and price. Stock is never touched: it comes from the count, not from old orders (D10). */
    public Mono<Void> fixLine(String orderId, String sku, int qty, BigDecimal unitPrice) {
        return db.sql("UPDATE order_lines SET qty = :qty, unit_price = :price WHERE order_id = :id AND sku_text = :sku")
                .bind("qty", qty).bind("price", unitPrice).bind("id", orderId).bind("sku", sku)
                .then();
    }

    /** The total follows the corrected lines. */
    public Mono<Void> recalculateTotal(String orderId) {
        return db.sql("""
                        UPDATE customer_orders
                        SET total_amount = (SELECT coalesce(sum(line_total), 0) FROM order_lines WHERE order_id = :id)
                        WHERE id = :id
                        """)
                .bind("id", orderId).then();
    }

    private static OrderRow toOrder(io.r2dbc.spi.Readable row) {
        return new OrderRow(row.get("id", String.class), row.get("store_id", String.class), row.get("status", String.class),
                row.get("created_at", OffsetDateTime.class), row.get("customer_name", String.class),
                row.get("total_amount", BigDecimal.class), row.get("review_status", String.class),
                row.get("review_note", String.class), row.get("reviewed_by", String.class),
                row.get("reviewed_by_name", String.class), row.get("reviewed_at", OffsetDateTime.class),
                row.get("version", Long.class));
    }
}
