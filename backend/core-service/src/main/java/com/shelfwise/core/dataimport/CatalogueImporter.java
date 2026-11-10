package com.shelfwise.core.dataimport;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;

import com.shelfwise.core.dataimport.CsvFile.Row;
import com.shelfwise.core.dataimport.ImportLog.Outcome;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Imports suppliers (derived from products.csv, D24), products.csv and inventory.csv.
 * Insert-only like every import step (D28): existing rows are skipped, never overwritten.
 */
@Component
class CatalogueImporter {

    static final String PRODUCTS_FILE = "products.csv";
    static final String INVENTORY_FILE = "inventory.csv";
    static final String SUPPLIERS = "suppliers (from products.csv)";

    private static final Set<String> STATUSES = Set.of("ACTIVE", "ARCHIVED");

    private enum Result { ADDED, SKIPPED, REJECTED }

    private final DatabaseClient db;
    private final ImportLog log;

    CatalogueImporter(DatabaseClient db, ImportLog log) {
        this.db = db;
        this.log = log;
    }

    /** The matching rule (D1) built from products.csv exactly as written; shared by every later file. */
    static SkuResolver resolverFor(List<Row> productRows) {
        Map<String, BigDecimal> rawSkuToPrice = new LinkedHashMap<>();
        productRows.forEach(row -> rawSkuToPrice.put(row.get("sku"), decimalOrNull(row.get("price_inr"))));
        return new SkuResolver(rawSkuToPrice);
    }

    /** Catalogue price per stored SKU (null if empty, D2): the reference D11 compares sales history against. */
    static Map<String, BigDecimal> pricesByStoredSku(List<Row> productRows) {
        Map<String, BigDecimal> prices = new java.util.HashMap<>();
        productRows.forEach(row -> prices.put(SkuResolver.storedSku(row.get("sku")), decimalOrNull(row.get("price_inr"))));
        return prices;
    }

    /** D24: there is no suppliers file, so suppliers are created from the IDs, named after them. */
    Mono<FileResult> importSuppliers(long runId, List<Row> productRows) {
        Set<String> ids = new TreeSet<>();
        productRows.forEach(row -> ids.add(row.get("supplier_id")));
        return Flux.fromIterable(ids)
                .concatMap(id -> db.sql("INSERT INTO suppliers (id, name) VALUES (:id, :name) ON CONFLICT (id) DO NOTHING")
                        .bind("id", id)
                        .bind("name", "Supplier " + id)
                        .fetch().rowsUpdated()
                        .flatMap(added -> added == 1
                                ? log.issue(runId, "D24", Outcome.INFO, "SUPPLIER", id,
                                        "Created from its ID (no suppliers file); details to be filled in by an admin")
                                        .thenReturn(Result.ADDED)
                                : Mono.just(Result.SKIPPED)))
                .reduce(FileResult.of(SUPPLIERS), CatalogueImporter::count);
    }

    Mono<FileResult> importProducts(long runId, List<Row> rows) {
        return Flux.fromIterable(rows)
                .concatMap(row -> {
                    String problem = validateProduct(row);
                    return problem != null
                            ? reject(runId, "PRODUCT", row.get("sku"), row, problem)
                            : insertProduct(runId, row);
                })
                .reduce(FileResult.of(PRODUCTS_FILE), CatalogueImporter::count);
    }

    Mono<FileResult> importInventory(long runId, List<Row> rows, SkuResolver skus) {
        return db.sql("SELECT id FROM stores").map((row, meta) -> row.get("id", String.class)).all()
                .collect(java.util.stream.Collectors.toSet())
                .flatMapMany(storeIds -> Flux.fromIterable(rows).concatMap(row -> {
                    SkuResolver.Match match = skus.forStockOrPurchaseRow(row.get("sku"));
                    String key = row.get("store_id") + "/" + row.get("sku");
                    if (match.storedSku().isEmpty()) {
                        return reject(runId, "INVENTORY", key, row, "unknown product '" + row.get("sku") + "'");
                    }
                    if (!storeIds.contains(row.get("store_id"))) {
                        return reject(runId, "INVENTORY", key, row, "unknown store '" + row.get("store_id") + "'");
                    }
                    Optional<Integer> quantity = integer(row.get("quantity_on_hand"));
                    Optional<Integer> reorderLevel = integer(row.get("reorder_level"));
                    Optional<OffsetDateTime> countedAt = ImportDates.parse(row.get("last_counted_at"));   // D6
                    if (quantity.isEmpty() || reorderLevel.isEmpty() || reorderLevel.get() < 0) {
                        return reject(runId, "INVENTORY", key, row, "quantity and reorder level must be whole numbers");
                    }
                    if (countedAt.isEmpty()) {
                        return reject(runId, "INVENTORY", key, row,
                                "last_counted_at '" + row.get("last_counted_at") + "' matches no known date format (D6)");
                    }
                    return insertInventory(runId, row, match.storedSku().get(), quantity.get(), reorderLevel.get(), countedAt.get());
                }))
                .reduce(FileResult.of(INVENTORY_FILE), CatalogueImporter::count);
    }

    // --- products ----------------------------------------------------------

    private Mono<Result> insertProduct(long runId, Row row) {
        String raw = row.get("sku");
        String sku = SkuResolver.storedSku(raw);
        BigDecimal price = decimalOrNull(row.get("price_inr"));
        DatabaseClient.GenericExecuteSpec insert = db.sql("""
                        INSERT INTO products (sku, name, category, price_inr, cost_price_inr, gst_rate, supplier_id,
                                              status, reorder_level, catalogued_on, source)
                        VALUES (:sku, :name, :category, :price, :cost, :gst, :supplier, :status, :reorder, :catalogued, 'IMPORT')
                        ON CONFLICT (sku) DO NOTHING
                        """)
                .bind("sku", sku)
                .bind("name", row.get("name"))
                .bind("category", row.get("category"))
                .bind("cost", new BigDecimal(row.get("cost_price_inr").strip()))
                .bind("gst", new BigDecimal(row.get("gst_rate").strip()))
                .bind("supplier", row.get("supplier_id"))
                .bind("status", row.get("status"))
                .bind("reorder", Integer.parseInt(row.get("reorder_level").strip()))
                .bind("catalogued", catalogueDate(row.get("created_at")).orElseThrow());
        insert = price == null ? insert.bindNull("price", BigDecimal.class) : insert.bind("price", price);

        return insert.fetch().rowsUpdated().flatMap(added -> {
            if (added == 0) {
                return Mono.just(Result.SKIPPED);
            }
            Mono<Void> notes = log.saveRaw(runId, PRODUCTS_FILE, row.line(), raw, row.values());
            if (SkuResolver.isVariant(raw)) {
                notes = notes.then(log.issue(runId, "D1", sku.equals(raw) ? Outcome.INFO : Outcome.CORRECTED, "PRODUCT", sku,
                        "Variant SKU '" + raw + "' kept as its own product, stored as '" + sku + "'"));
            }
            if (price == null) {
                notes = notes.then(log.issue(runId, "D2", Outcome.FLAGGED, "PRODUCT", sku,
                        "No selling price: blocked from customer orders until an admin sets one"));
            }
            if ("ARCHIVED".equals(row.get("status"))) {
                notes = notes.then(log.issue(runId, "D3", Outcome.INFO, "PRODUCT", sku,
                        "Archived: stock and history kept; no new orders or purchase orders"));
            }
            return notes.thenReturn(Result.ADDED);
        });
    }

    private static String validateProduct(Row row) {
        if (blank(row.get("sku")) || blank(row.get("name")) || blank(row.get("category")) || blank(row.get("supplier_id"))) {
            return "sku, name, category and supplier_id are required";
        }
        if (!STATUSES.contains(row.get("status"))) {
            return "unknown status '" + row.get("status") + "'";
        }
        BigDecimal price = decimalOrNull(row.get("price_inr"));
        if (!blank(row.get("price_inr")) && (price == null || price.signum() <= 0)) {
            return "price_inr must be a positive number or empty, was '" + row.get("price_inr") + "'";
        }
        BigDecimal cost = decimalOrNull(row.get("cost_price_inr"));
        BigDecimal gst = decimalOrNull(row.get("gst_rate"));
        if (cost == null || cost.signum() < 0 || gst == null || gst.signum() < 0) {
            return "cost_price_inr and gst_rate must be numbers of at least 0";
        }
        if (integer(row.get("reorder_level")).filter(level -> level >= 0).isEmpty()) {
            return "reorder_level must be a whole number of at least 0";
        }
        if (catalogueDate(row.get("created_at")).isEmpty()) {
            return "created_at '" + row.get("created_at") + "' matches no known date format";
        }
        return null;
    }

    /** D30: created_at is read as "added to the catalogue on", in IST. */
    private static Optional<LocalDate> catalogueDate(String value) {
        return ImportDates.parse(value).map(moment -> moment.atZoneSameInstant(ImportDates.IST).toLocalDate());
    }

    // --- inventory ---------------------------------------------------------

    private Mono<Result> insertInventory(long runId, Row row, String sku, int quantity, int reorderLevel, OffsetDateTime countedAt) {
        boolean negative = quantity < 0;
        String key = row.get("store_id") + "/" + sku;
        return db.sql("""
                        INSERT INTO inventory (store_id, sku, quantity, reorder_level, last_counted_at, recount_needed)
                        VALUES (:store, :sku, :quantity, :reorder, :counted, :recount)
                        ON CONFLICT (store_id, sku) DO NOTHING
                        """)
                .bind("store", row.get("store_id"))
                .bind("sku", sku)
                .bind("quantity", negative ? 0 : quantity)   // D5: stock can never be below 0
                .bind("reorder", reorderLevel)
                .bind("counted", countedAt)
                .bind("recount", negative)
                .fetch().rowsUpdated()
                .flatMap(added -> {
                    if (added == 0) {
                        return Mono.just(Result.SKIPPED);
                    }
                    Mono<Void> notes = log.saveRaw(runId, INVENTORY_FILE, row.line(), key, row.values());
                    if (negative) {
                        notes = notes
                                .then(db.sql("""
                                        INSERT INTO stock_movements (store_id, sku, delta, quantity_after, reason, note)
                                        VALUES (:store, :sku, :delta, 0, 'IMPORT_CORRECTION', :note)
                                        """)
                                        .bind("store", row.get("store_id"))
                                        .bind("sku", sku)
                                        .bind("delta", -quantity)
                                        .bind("note", "Import correction: " + quantity + " -> 0, recount needed (D5)")
                                        .then())
                                .then(log.issue(runId, "D5", Outcome.CORRECTED, "INVENTORY", key,
                                        "Negative stock " + quantity + " imported as 0 and flagged for recount"));
                    }
                    return notes.thenReturn(Result.ADDED);
                });
    }

    // --- helpers -----------------------------------------------------------

    private Mono<Result> reject(long runId, String entityType, String entityId, Row row, String reason) {
        return log.issue(runId, "VALIDATION", Outcome.REJECTED, entityType, entityId, "Line " + row.line() + ": " + reason)
                .thenReturn(Result.REJECTED);
    }

    private static FileResult count(FileResult result, Result outcome) {
        return switch (outcome) {
            case ADDED -> result.plusAdded();
            case SKIPPED -> result.plusSkipped();
            case REJECTED -> result.plusRejected();
        };
    }

    static BigDecimal decimalOrNull(String value) {
        if (blank(value)) {
            return null;
        }
        try {
            return new BigDecimal(value.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Optional<Integer> integer(String value) {
        try {
            return Optional.of(Integer.parseInt(value.strip()));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
