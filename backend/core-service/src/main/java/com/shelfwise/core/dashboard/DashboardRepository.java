package com.shelfwise.core.dashboard;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;

import com.shelfwise.core.dashboard.DashboardDtos.TopProduct;
import com.shelfwise.core.dashboard.DashboardDtos.TrendPoint;
import com.shelfwise.core.dashboard.DashboardDtos.UnknownProducts;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Dashboard figures, computed from the orders every time (no stored totals that could drift).
 * Every query leaves out orders still waiting for admin review (D10): only unflagged or corrected ones count.
 */
@Repository
public class DashboardRepository {

    /** Orders that count anywhere on the dashboard (D10). */
    static final String COUNTED = "(o.review_status IS NULL OR o.review_status = 'CORRECTED')";

    public record Range(String storeId, OffsetDateTime from, OffsetDateTime to) {
    }

    private final DatabaseClient db;

    public DashboardRepository(DatabaseClient db) {
        this.db = db;
    }

    public Mono<Map<String, Object>> totals(Range r) {
        return bind(db.sql("""
                        SELECT coalesce(sum(o.total_amount) FILTER (WHERE o.status = 'DELIVERED'), 0) AS revenue,
                               count(*) FILTER (WHERE o.status = 'DELIVERED') AS delivered,
                               count(*) AS orders,
                               count(*) FILTER (WHERE o.status = 'PENDING') AS pending,
                               count(*) FILTER (WHERE o.status = 'PACKED') AS packed,
                               count(*) FILTER (WHERE o.status = 'SHIPPED') AS shipped,
                               count(*) FILTER (WHERE o.status = 'CANCELLED') AS cancelled
                        FROM customer_orders o
                        WHERE\s""" + COUNTED + filters(r)), r)
                .map((row, meta) -> {
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("revenue", row.get("revenue", BigDecimal.class));
                    t.put("delivered", row.get("delivered", Long.class));
                    t.put("orders", row.get("orders", Long.class));
                    Map<String, Long> byStatus = new LinkedHashMap<>();
                    for (String s : new String[] {"pending", "packed", "shipped", "delivered", "cancelled"}) {
                        byStatus.put(s.toUpperCase(), row.get(s, Long.class));
                    }
                    t.put("byStatus", byStatus);
                    return t;
                })
                .one();
    }

    public Flux<TopProduct> topProducts(Range r, int limit) {
        return bind(db.sql("""
                        SELECT l.product_sku AS sku, p.name, sum(l.qty) AS units, sum(l.line_total) AS revenue
                        FROM order_lines l
                        JOIN customer_orders o ON o.id = l.order_id
                        JOIN products p ON p.sku = l.product_sku
                        WHERE o.status = 'DELIVERED' AND\s""" + COUNTED + filters(r) + """

                        GROUP BY l.product_sku, p.name
                        ORDER BY revenue DESC, l.product_sku
                        LIMIT :limit
                        """), r)
                .bind("limit", limit)
                .map((row, meta) -> new TopProduct(row.get("sku", String.class), row.get("name", String.class),
                        row.get("units", Long.class), row.get("revenue", BigDecimal.class)))
                .all();
    }

    public Mono<UnknownProducts> unknownProducts(Range r) {
        return bind(db.sql("""
                        SELECT coalesce(sum(l.qty), 0) AS units, coalesce(sum(l.line_total), 0) AS revenue
                        FROM order_lines l JOIN customer_orders o ON o.id = l.order_id
                        WHERE l.product_sku IS NULL AND o.status = 'DELIVERED' AND\s""" + COUNTED + filters(r)), r)
                .map((row, meta) -> new UnknownProducts(row.get("units", Long.class), row.get("revenue", BigDecimal.class)))
                .one();
    }

    /** Per store and IST day (or month): delivered revenue and orders placed. */
    public Flux<TrendPoint> trend(Range r, String unit) {
        String period = "date_trunc('" + ("month".equals(unit) ? "month" : "day") + "', o.created_at AT TIME ZONE 'Asia/Kolkata')::date";
        return bind(db.sql("SELECT " + period + " AS period, o.store_id,"
                        + " coalesce(sum(o.total_amount) FILTER (WHERE o.status = 'DELIVERED'), 0) AS revenue, count(*) AS orders"
                        + " FROM customer_orders o WHERE " + COUNTED + filters(r)
                        + " GROUP BY 1, 2 ORDER BY 1, 2"), r)
                .map((row, meta) -> new TrendPoint(row.get("period", LocalDate.class), row.get("store_id", String.class),
                        row.get("revenue", BigDecimal.class), row.get("orders", Long.class)))
                .all();
    }

    /** {low, out}: items at or below the reorder level right now, of which out of stock; archived products don't count. */
    public Mono<long[]> lowStock(String storeId) {
        DatabaseClient.GenericExecuteSpec spec = db.sql("""
                SELECT count(*) FILTER (WHERE i.quantity <= i.reorder_level) AS low, count(*) FILTER (WHERE i.quantity = 0) AS out
                FROM inventory i JOIN products p ON p.sku = i.sku
                WHERE p.status = 'ACTIVE'""" + (storeId == null ? "" : " AND i.store_id = :store"));
        if (storeId != null) {
            spec = spec.bind("store", storeId);
        }
        return spec.map((row, meta) -> new long[] {row.get("low", Long.class), row.get("out", Long.class)}).one();
    }

    // --- helpers -------------------------------------------------------------

    private static String filters(Range r) {
        return (r.storeId() == null ? "" : " AND o.store_id = :store")
                + (r.from() == null ? "" : " AND o.created_at >= :from")
                + (r.to() == null ? "" : " AND o.created_at < :to");
    }

    private static DatabaseClient.GenericExecuteSpec bind(DatabaseClient.GenericExecuteSpec spec, Range r) {
        if (r.storeId() != null) {
            spec = spec.bind("store", r.storeId());
        }
        if (r.from() != null) {
            spec = spec.bind("from", r.from());
        }
        if (r.to() != null) {
            spec = spec.bind("to", r.to());
        }
        return spec;
    }
}
