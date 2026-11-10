package com.shelfwise.core.inventory;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;

import com.shelfwise.core.inventory.InventoryDtos.Movement;
import com.shelfwise.core.inventory.InventoryDtos.StockItem;

import io.r2dbc.spi.Row;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Stock queries and the atomic stock updates that keep quantity >= 0 under concurrent changes. */
@Repository
public class InventoryRepository {

    static final Map<String, String> SORT_COLUMNS = Map.of(
            "name", "p.name",
            "sku", "i.sku",
            "quantity", "i.quantity",
            "reorderLevel", "i.reorder_level",
            "store", "i.store_id");

    private static final String COLUMNS = """
            i.store_id, i.sku, p.name, p.category, p.status, p.price_inr, i.quantity, i.reorder_level,
            i.recount_needed, i.last_counted_at, i.version
            """;

    public record Search(String storeId, String text, boolean lowOnly, boolean recountOnly,
                         String sortKey, boolean descending, int page, int size) {
    }

    private final DatabaseClient db;

    public InventoryRepository(DatabaseClient db) {
        this.db = db;
    }

    public Flux<StockItem> search(Search s) {
        Where w = where(s);
        String order = SORT_COLUMNS.get(s.sortKey()) + (s.descending() ? " DESC" : " ASC") + ", i.store_id, i.sku";
        return bindAll(db.sql("SELECT " + COLUMNS + " FROM inventory i JOIN products p ON p.sku = i.sku" + w.sql
                        + " ORDER BY " + order + " LIMIT :limit OFFSET :offset"), w.params)
                .bind("limit", s.size()).bind("offset", (long) s.page() * s.size())
                .map((row, meta) -> toItem(row)).all();
    }

    public Mono<Long> count(Search s) {
        Where w = where(s);
        return bindAll(db.sql("SELECT count(*) FROM inventory i JOIN products p ON p.sku = i.sku" + w.sql), w.params)
                .map((row, meta) -> row.get(0, Long.class)).one();
    }

    public Mono<StockItem> find(String storeId, String sku) {
        return db.sql("SELECT " + COLUMNS + " FROM inventory i JOIN products p ON p.sku = i.sku WHERE i.store_id = :store AND i.sku = :sku")
                .bind("store", storeId).bind("sku", sku)
                .map((row, meta) -> toItem(row)).one();
    }

    /**
     * Adds {@code delta} (negative to take away) in ONE statement that only succeeds if the result stays >= 0.
     * Two concurrent changes can't both pass the check on stale data: the database applies them one after
     * the other on the row. Returns the new quantity, or empty if it would go below 0 (or the row doesn't exist).
     */
    public Mono<Integer> addAtomically(String storeId, String sku, int delta) {
        return db.sql("""
                        UPDATE inventory SET quantity = quantity + :delta, version = version + 1, updated_at = now()
                        WHERE store_id = :store AND sku = :sku AND quantity + :delta >= 0
                        RETURNING quantity
                        """)
                .bind("delta", delta).bind("store", storeId).bind("sku", sku)
                .map((row, meta) -> row.get(0, Integer.class)).one();
    }

    /**
     * Goods received from a supplier: adds stock, creating the store's stock row if this product
     * was never stocked there. One statement, so concurrent receipts can't lose an update.
     */
    public Mono<Integer> receive(String storeId, String sku, int qty) {
        return db.sql("""
                        INSERT INTO inventory (store_id, sku, quantity, reorder_level)
                        SELECT :store, :sku, :qty, p.reorder_level FROM products p WHERE p.sku = :sku
                        ON CONFLICT (store_id, sku) DO UPDATE
                           SET quantity = inventory.quantity + EXCLUDED.quantity, version = inventory.version + 1, updated_at = now()
                        RETURNING quantity
                        """)
                .bind("store", storeId).bind("sku", sku).bind("qty", qty)
                .map((row, meta) -> row.get(0, Integer.class)).one();
    }

    /** Recount: sets the counted quantity and clears "recount needed" (D5). Returns {old, new} in one statement. */
    public Mono<int[]> setCounted(String storeId, String sku, int counted) {
        return db.sql("""
                        UPDATE inventory i SET quantity = :counted, recount_needed = false, last_counted_at = now(),
                               version = i.version + 1, updated_at = now()
                        FROM (SELECT quantity AS old FROM inventory WHERE store_id = :store AND sku = :sku FOR UPDATE) o
                        WHERE i.store_id = :store AND i.sku = :sku
                        RETURNING o.old, i.quantity
                        """)
                .bind("counted", counted).bind("store", storeId).bind("sku", sku)
                .map((row, meta) -> new int[] {row.get(0, Integer.class), row.get(1, Integer.class)}).one();
    }

    public Mono<Movement> recordMovement(String storeId, String sku, int delta, int quantityAfter, String reason,
                                         String note, String referenceType, String referenceId, String actorUserId) {
        DatabaseClient.GenericExecuteSpec spec = db.sql("""
                        INSERT INTO stock_movements (store_id, sku, delta, quantity_after, reason, note, reference_type, reference_id, actor_user_id)
                        VALUES (:store, :sku, :delta, :after, :reason, :note, :refType, :refId, :actor)
                        RETURNING id, delta, quantity_after, reason, note, reference_type, reference_id, actor_user_id, created_at
                        """)
                .bind("store", storeId).bind("sku", sku).bind("delta", delta).bind("after", quantityAfter).bind("reason", reason);
        spec = note == null ? spec.bindNull("note", String.class) : spec.bind("note", note);
        spec = referenceType == null ? spec.bindNull("refType", String.class) : spec.bind("refType", referenceType);
        spec = referenceId == null ? spec.bindNull("refId", String.class) : spec.bind("refId", referenceId);
        spec = actorUserId == null ? spec.bindNull("actor", String.class) : spec.bind("actor", actorUserId);
        return spec.map((row, meta) -> toMovement(row)).one();
    }

    public Flux<Movement> history(String storeId, String sku, int page, int size) {
        return db.sql("""
                        SELECT id, delta, quantity_after, reason, note, reference_type, reference_id, actor_user_id, created_at
                        FROM stock_movements WHERE store_id = :store AND sku = :sku
                        ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset
                        """)
                .bind("store", storeId).bind("sku", sku).bind("limit", size).bind("offset", (long) page * size)
                .map((row, meta) -> toMovement(row)).all();
    }

    // --- helpers -----------------------------------------------------------

    private record Where(String sql, Map<String, Object> params) {
    }

    private static Where where(Search s) {
        List<String> conditions = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();
        if (s.storeId() != null) {
            conditions.add("i.store_id = :store");
            params.put("store", s.storeId());
        }
        if (s.text() != null && !s.text().isBlank()) {
            conditions.add("(p.name ILIKE :text ESCAPE '\\' OR i.sku ILIKE :text ESCAPE '\\')");
            params.put("text", "%" + s.text().strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (s.lowOnly()) {
            conditions.add("i.quantity <= i.reorder_level");
        }
        if (s.recountOnly()) {
            conditions.add("i.recount_needed");
        }
        return new Where(conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions), params);
    }

    private static DatabaseClient.GenericExecuteSpec bindAll(DatabaseClient.GenericExecuteSpec spec, Map<String, Object> params) {
        for (Map.Entry<String, Object> p : params.entrySet()) {
            spec = spec.bind(p.getKey(), p.getValue());
        }
        return spec;
    }

    private static StockItem toItem(Row row) {
        int quantity = row.get("quantity", Integer.class);
        int reorder = row.get("reorder_level", Integer.class);
        return new StockItem(row.get("store_id", String.class), row.get("sku", String.class), row.get("name", String.class),
                row.get("category", String.class), row.get("status", String.class), row.get("price_inr", BigDecimal.class),
                quantity, reorder, quantity <= reorder, Boolean.TRUE.equals(row.get("recount_needed", Boolean.class)),
                row.get("last_counted_at", OffsetDateTime.class), row.get("version", Long.class));
    }

    private static Movement toMovement(Row row) {
        return new Movement(row.get("id", Long.class), row.get("delta", Integer.class), row.get("quantity_after", Integer.class),
                row.get("reason", String.class), row.get("note", String.class), row.get("reference_type", String.class),
                row.get("reference_id", String.class), row.get("actor_user_id", String.class),
                row.get("created_at", OffsetDateTime.class));
    }
}
