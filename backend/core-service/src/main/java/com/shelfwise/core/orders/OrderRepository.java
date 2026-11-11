package com.shelfwise.core.orders;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;

import com.shelfwise.core.orders.OrderDtos.LineView;

import io.r2dbc.spi.Row;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Order queries and the atomic status change. */
@Repository
public class OrderRepository {

    /** An order row as stored; lines are loaded separately. */
    public record OrderRow(String id, String storeId, String placedBy, String status, String customerName, String customerPhone,
                           BigDecimal totalAmount, OffsetDateTime createdAt, String reviewStatus, String source,
                           String idempotencyKey, long version) {
    }

    public record Search(String storeId, String status, String text, OffsetDateTime from, OffsetDateTime to, int page, int size) {
    }

    private static final String COLUMNS = """
            id, store_id, placed_by, status, customer_name, customer_phone, total_amount, created_at,
            review_status, source, idempotency_key, version
            """;

    private final DatabaseClient db;

    public OrderRepository(DatabaseClient db) {
        this.db = db;
    }

    /** A person's name for display (N6); the ID if the user is unknown. */
    public Mono<String> userName(String userId) {
        return db.sql("SELECT name FROM users WHERE id = :id")
                .bind("id", userId)
                .map((row, meta) -> row.get("name", String.class))
                .one()
                .defaultIfEmpty(userId);
    }

    public Flux<OrderRow> search(Search s) {
        Where w = where(s);
        return bindAll(db.sql("SELECT " + COLUMNS + " FROM customer_orders" + w.sql
                        + " ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset"), w.params)
                .bind("limit", s.size()).bind("offset", (long) s.page() * s.size())
                .map((row, meta) -> toOrder(row)).all();
    }

    public Mono<Long> count(Search s) {
        Where w = where(s);
        return bindAll(db.sql("SELECT count(*) FROM customer_orders" + w.sql), w.params).map((row, meta) -> row.get(0, Long.class)).one();
    }

    public Mono<OrderRow> find(String id) {
        return db.sql("SELECT " + COLUMNS + " FROM customer_orders WHERE id = :id").bind("id", id).map((row, meta) -> toOrder(row)).one();
    }

    public Mono<OrderRow> findByIdempotencyKey(String key) {
        return db.sql("SELECT " + COLUMNS + " FROM customer_orders WHERE idempotency_key = :key").bind("key", key)
                .map((row, meta) -> toOrder(row)).one();
    }

    public Flux<LineView> lines(String orderId) {
        return db.sql("""
                        SELECT l.sku_text, l.product_sku, p.name, l.qty, l.unit_price, l.line_total
                        FROM order_lines l LEFT JOIN products p ON p.sku = l.product_sku
                        WHERE l.order_id = :id ORDER BY l.id
                        """)
                .bind("id", orderId)
                .map((row, meta) -> new LineView(row.get("sku_text", String.class), row.get("product_sku", String.class),
                        row.get("name", String.class), row.get("qty", Integer.class), row.get("unit_price", BigDecimal.class),
                        row.get("line_total", BigDecimal.class)))
                .all();
    }

    /** Next app order number, e.g. ORD-100000 (V2 migration). */
    public Mono<String> nextOrderId() {
        return db.sql("SELECT 'ORD-' || lpad(nextval('order_number_seq')::text, 6, '0')").map((row, meta) -> row.get(0, String.class)).one();
    }

    public Mono<Void> insertOrder(String id, String storeId, String placedBy, String name, String phone, BigDecimal total,
                                  String idempotencyKey) {
        DatabaseClient.GenericExecuteSpec spec = db.sql("""
                        INSERT INTO customer_orders (id, store_id, placed_by, status, customer_name, customer_phone, total_amount,
                                                     created_at, source, idempotency_key)
                        VALUES (:id, :store, :placedBy, 'PENDING', :name, :phone, :total, now(), 'APP', :key)
                        """)
                .bind("id", id).bind("store", storeId).bind("placedBy", placedBy).bind("name", name).bind("phone", phone)
                .bind("total", total);
        return (idempotencyKey == null ? spec.bindNull("key", String.class) : spec.bind("key", idempotencyKey)).then();
    }

    public Mono<Void> insertLine(String orderId, String sku, int qty, BigDecimal unitPrice) {
        return db.sql("""
                        INSERT INTO order_lines (order_id, sku_text, product_sku, qty, unit_price, price_source, imported)
                        VALUES (:order, :sku, :sku, :qty, :price, 'RECORDED', false)
                        """)
                .bind("order", orderId).bind("sku", sku).bind("qty", qty).bind("price", unitPrice).then();
    }

    /** Moves the order from one status to another only if nobody changed it since (version) and it's still in {@code from}. */
    public Mono<Boolean> changeStatus(String id, String from, String to, long version) {
        return db.sql("""
                        UPDATE customer_orders SET status = :to, version = version + 1, updated_at = now()
                        WHERE id = :id AND status = :from AND version = :version
                        """)
                .bind("id", id).bind("from", from).bind("to", to).bind("version", version)
                .fetch().rowsUpdated().map(n -> n == 1);
    }

    // --- helpers -----------------------------------------------------------

    private record Where(String sql, Map<String, Object> params) {
    }

    private static Where where(Search s) {
        List<String> conditions = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();
        if (s.storeId() != null) {
            conditions.add("store_id = :store");
            params.put("store", s.storeId());
        }
        if (s.status() != null && !s.status().isBlank()) {
            conditions.add("status = :status");
            params.put("status", s.status());
        }
        if (s.text() != null && !s.text().isBlank()) {
            conditions.add("(id ILIKE :text ESCAPE '\\' OR customer_name ILIKE :text ESCAPE '\\')");
            params.put("text", "%" + s.text().strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (s.from() != null) {
            conditions.add("created_at >= :from");
            params.put("from", s.from());
        }
        if (s.to() != null) {
            conditions.add("created_at < :to");
            params.put("to", s.to());
        }
        return new Where(conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions), params);
    }

    private static DatabaseClient.GenericExecuteSpec bindAll(DatabaseClient.GenericExecuteSpec spec, Map<String, Object> params) {
        for (Map.Entry<String, Object> p : params.entrySet()) {
            spec = spec.bind(p.getKey(), p.getValue());
        }
        return spec;
    }

    private static OrderRow toOrder(Row row) {
        return new OrderRow(row.get("id", String.class), row.get("store_id", String.class), row.get("placed_by", String.class),
                row.get("status", String.class), row.get("customer_name", String.class), row.get("customer_phone", String.class),
                row.get("total_amount", BigDecimal.class), row.get("created_at", OffsetDateTime.class),
                row.get("review_status", String.class), row.get("source", String.class), row.get("idempotency_key", String.class),
                row.get("version", Long.class));
    }
}
