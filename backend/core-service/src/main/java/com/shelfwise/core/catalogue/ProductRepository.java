package com.shelfwise.core.catalogue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;

import io.r2dbc.spi.Row;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Product queries. Search values are always bound as parameters; sort columns come from a fixed list. */
@Repository
public class ProductRepository {

    /** What the API accepts as a sort key, and the column it maps to. Anything else is rejected. */
    static final Map<String, String> SORT_COLUMNS = Map.of(
            "name", "name",
            "sku", "sku",
            "category", "category",
            "price", "price_inr",
            "reorderLevel", "reorder_level");

    private static final String COLUMNS = """
            sku, name, category, price_inr, cost_price_inr, gst_rate, supplier_id, status, reorder_level, catalogued_on, version
            """;

    public record Search(String text, String category, String status, String sortKey, boolean descending, int page, int size) {
    }

    private final DatabaseClient db;

    public ProductRepository(DatabaseClient db) {
        this.db = db;
    }

    public Flux<Product> search(Search s) {
        Where where = where(s);
        String order = SORT_COLUMNS.get(s.sortKey()) + (s.descending() ? " DESC" : " ASC") + " NULLS LAST, sku ASC";
        DatabaseClient.GenericExecuteSpec spec = db.sql("SELECT " + COLUMNS + " FROM products" + where.sql
                + " ORDER BY " + order + " LIMIT :limit OFFSET :offset");
        spec = bindAll(spec, where.params).bind("limit", s.size()).bind("offset", (long) s.page() * s.size());
        return spec.map((row, meta) -> toProduct(row)).all();
    }

    public Mono<Long> count(Search s) {
        Where where = where(s);
        return bindAll(db.sql("SELECT count(*) FROM products" + where.sql), where.params)
                .map((row, meta) -> row.get(0, Long.class)).one();
    }

    public Mono<Product> findBySku(String sku) {
        return db.sql("SELECT " + COLUMNS + " FROM products WHERE sku = :sku")
                .bind("sku", sku).map((row, meta) -> toProduct(row)).one();
    }

    /** True if a product with this SKU exists ignoring case: a new 'SW-ABC-00001' would clash with 'sw-abc-00001' (D1). */
    public Mono<Boolean> existsIgnoringCase(String sku) {
        return db.sql("SELECT count(*) FROM products WHERE lower(sku) = lower(:sku)")
                .bind("sku", sku).map((row, meta) -> row.get(0, Long.class) > 0).one();
    }

    public Flux<String> categories() {
        return db.sql("SELECT DISTINCT category FROM products ORDER BY category").map((row, meta) -> row.get(0, String.class)).all();
    }

    public Mono<Boolean> supplierExists(String supplierId) {
        return db.sql("SELECT count(*) FROM suppliers WHERE id = :id AND active")
                .bind("id", supplierId).map((row, meta) -> row.get(0, Long.class) > 0).one();
    }

    public Mono<Void> insert(Product p) {
        return db.sql("""
                        INSERT INTO products (sku, name, category, price_inr, cost_price_inr, gst_rate, supplier_id,
                                              status, reorder_level, catalogued_on, source)
                        VALUES (:sku, :name, :category, :price, :cost, :gst, :supplier, 'ACTIVE', :reorder, :catalogued, 'APP')
                        """)
                .bind("sku", p.sku()).bind("name", p.name()).bind("category", p.category())
                .bind("price", p.priceInr()).bind("cost", p.costPriceInr()).bind("gst", p.gstRate())
                .bind("supplier", p.supplierId()).bind("reorder", p.reorderLevel()).bind("catalogued", p.cataloguedOn())
                .then();
    }

    /**
     * Saves an edit only if nobody saved since the admin loaded it (optimistic check, the D31 pattern).
     * Returns false when the version no longer matches.
     */
    public Mono<Boolean> update(Product p, long expectedVersion) {
        DatabaseClient.GenericExecuteSpec spec = db.sql("""
                        UPDATE products SET name = :name, category = :category, price_inr = :price, cost_price_inr = :cost,
                               gst_rate = :gst, supplier_id = :supplier, reorder_level = :reorder,
                               version = version + 1, updated_at = now()
                        WHERE sku = :sku AND version = :version
                        """)
                .bind("sku", p.sku()).bind("name", p.name()).bind("category", p.category())
                .bind("cost", p.costPriceInr()).bind("gst", p.gstRate()).bind("supplier", p.supplierId())
                .bind("reorder", p.reorderLevel()).bind("version", expectedVersion);
        spec = p.priceInr() == null ? spec.bindNull("price", BigDecimal.class) : spec.bind("price", p.priceInr());
        return spec.fetch().rowsUpdated().map(n -> n == 1);
    }

    /** Changes status from {@code from} to {@code to} only if the version still matches. */
    public Mono<Boolean> changeStatus(String sku, String from, String to, long expectedVersion) {
        return db.sql("""
                        UPDATE products SET status = :to, version = version + 1, updated_at = now()
                        WHERE sku = :sku AND status = :from AND version = :version
                        """)
                .bind("sku", sku).bind("from", from).bind("to", to).bind("version", expectedVersion)
                .fetch().rowsUpdated().map(n -> n == 1);
    }

    // --- helpers -----------------------------------------------------------

    private record Where(String sql, Map<String, Object> params) {
    }

    private static Where where(Search s) {
        List<String> conditions = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();
        if (!"ALL".equals(s.status())) {
            conditions.add("status = :status");
            params.put("status", s.status());
        }
        if (s.category() != null && !s.category().isBlank()) {
            conditions.add("category = :category");
            params.put("category", s.category());
        }
        if (s.text() != null && !s.text().isBlank()) {
            conditions.add("(name ILIKE :text ESCAPE '\\' OR sku ILIKE :text ESCAPE '\\')");
            params.put("text", "%" + escapeLike(s.text().strip()) + "%");
        }
        return new Where(conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions), params);
    }

    /** So that a search for "50%" means the characters 50% and not "50 followed by anything". */
    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static DatabaseClient.GenericExecuteSpec bindAll(DatabaseClient.GenericExecuteSpec spec, Map<String, Object> params) {
        for (Map.Entry<String, Object> p : params.entrySet()) {
            spec = spec.bind(p.getKey(), p.getValue());
        }
        return spec;
    }

    private static Product toProduct(Row row) {
        return new Product(
                row.get("sku", String.class),
                row.get("name", String.class),
                row.get("category", String.class),
                row.get("price_inr", BigDecimal.class),
                row.get("cost_price_inr", BigDecimal.class),
                row.get("gst_rate", BigDecimal.class),
                row.get("supplier_id", String.class),
                row.get("status", String.class),
                row.get("reorder_level", Integer.class),
                row.get("catalogued_on", LocalDate.class),
                row.get("version", Long.class));
    }
}
