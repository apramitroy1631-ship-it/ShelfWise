package com.shelfwise.core.dataimport;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;

import com.shelfwise.core.dataimport.ImportLog.Outcome;

import io.r2dbc.postgresql.codec.Json;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Imports orders.json: decisions D8-D16. Imported orders never change stock (D29: inventory.csv already reflects them).
 * Everything is decided in memory first, because D11 needs the prices of all other orders, then written in batches.
 */
@Component
class OrderImporter {

    static final String ORDERS_FILE = "orders.json";

    private static final Set<String> STATUSES = Set.of("PENDING", "PACKED", "SHIPPED", "DELIVERED", "CANCELLED");
    private static final Duration HISTORY_WINDOW = Duration.ofDays(30);

    private final DatabaseClient db;
    private final ImportLog log;
    private final ObjectMapper json;

    OrderImporter(DatabaseClient db, ImportLog log, ObjectMapper json) {
        this.db = db;
        this.log = log;
        this.json = json;
    }

    // --- in-memory model ---------------------------------------------------

    /** One order line as decided. productSku is null for an unknown product (D12). */
    private record Line(String rawSku, String productSku, int qty, BigDecimal price, String priceSource) {

        BigDecimal total() {
            return price.multiply(BigDecimal.valueOf(qty));
        }
    }

    private record Note(String decision, Outcome outcome, String message) {
    }

    private static final class Order {
        String id;
        int position;
        JsonNode raw;
        String store;
        String placedBy;
        String status;
        OffsetDateTime createdAt;
        String customerName;
        String customerPhone;
        BigDecimal fileTotal;
        List<RawLine> rawLines = new ArrayList<>();
        List<Line> lines = new ArrayList<>();
        BigDecimal total;
        String reviewStatus;
        String reviewNote;
        final List<Note> notes = new ArrayList<>();
    }

    /** A line as read, with its matched product (D1 ladder, D13). */
    private record RawLine(String rawSku, SkuResolver.Match match, int qty, BigDecimal price) {
        String productSku() {
            return match.storedSku().orElse(null);
        }
    }

    private record Sale(OffsetDateTime at, BigDecimal price) {
    }

    private enum Counted { ADDED, SKIPPED, REJECTED }

    // --- import ------------------------------------------------------------

    Mono<FileResult> importOrders(long runId, Path file, SkuResolver skus, Map<String, BigDecimal> cataloguePrices) {
        Mono<JsonNode> records = Mono.fromCallable(() -> json.readTree(Files.readString(file)))
                .subscribeOn(Schedulers.boundedElastic());
        return Mono.zip(records, ids("SELECT id FROM customer_orders"), ids("SELECT id FROM stores"), ids("SELECT id FROM users"))
                .flatMap(t -> {
                    JsonNode root = t.getT1();
                    Set<String> existing = t.getT2();
                    Set<String> stores = t.getT3();
                    Set<String> users = t.getT4();

                    // D8: group copies of the same order ID, keeping the first copy's position
                    Map<String, List<Integer>> copies = new LinkedHashMap<>();
                    for (int i = 0; i < root.size(); i++) {
                        copies.computeIfAbsent(root.get(i).path("order_id").asString(), k -> new ArrayList<>()).add(i);
                    }

                    List<Order> toInsert = new ArrayList<>();
                    List<Mono<Void>> rejections = new ArrayList<>();
                    int[] counts = new int[3];   // added, skipped, rejected

                    for (Map.Entry<String, List<Integer>> entry : copies.entrySet()) {
                        String id = entry.getKey();
                        List<Integer> positions = entry.getValue();
                        if (existing.contains(id)) {
                            counts[Counted.SKIPPED.ordinal()] += positions.size();
                            continue;
                        }
                        JsonNode first = root.get(positions.get(0));
                        boolean identical = positions.stream().allMatch(p -> root.get(p).equals(first));
                        if (!identical) {
                            counts[Counted.REJECTED.ordinal()]++;
                            rejections.add(log.issue(runId, "D8", Outcome.REJECTED, "ORDER", id,
                                    positions.size() + " records share this ID with different content; none imported, needs review"));
                            continue;
                        }
                        Order order = new Order();
                        order.id = id;
                        order.position = positions.get(0);
                        order.raw = first;
                        String problem = read(order, first, stores, users, skus);
                        if (problem != null) {
                            counts[Counted.REJECTED.ordinal()]++;
                            rejections.add(log.issue(runId, "VALIDATION", Outcome.REJECTED, "ORDER", id,
                                    "Array index " + order.position + ": " + problem));
                            continue;
                        }
                        if (positions.size() > 1) {
                            counts[Counted.SKIPPED.ordinal()] += positions.size() - 1;
                            order.notes.add(new Note("D8", Outcome.SKIPPED,
                                    (positions.size() - 1) + " identical copy skipped; imported once"));
                        }
                        toInsert.add(order);
                    }

                    Map<String, List<Sale>> history = salesHistory(toInsert);
                    List<Order> ready = new ArrayList<>();
                    for (Order order : toInsert) {
                        String problem = decide(order, history, cataloguePrices);
                        if (problem != null) {
                            counts[Counted.REJECTED.ordinal()]++;
                            rejections.add(log.issue(runId, "D14", Outcome.REJECTED, "ORDER", order.id, problem));
                        } else {
                            ready.add(order);
                        }
                    }
                    counts[Counted.ADDED.ordinal()] = ready.size();

                    return Flux.concat(rejections).then(write(runId, ready))
                            .thenReturn(new FileResult(ORDERS_FILE, counts[0], counts[1], counts[2]));
                });
    }

    /** Reads one record; returns why it is rejected, or null. Matches each line's product (D1, D12, D13). */
    private static String read(Order order, JsonNode node, Set<String> stores, Set<String> users, SkuResolver skus) {
        order.store = node.path("store_id").asString();
        order.placedBy = node.path("placed_by").asString();
        order.status = node.path("status").asString();
        JsonNode created = node.path("created_at");
        Optional<OffsetDateTime> createdAt = created.isNumber()
                ? Optional.of(ImportDates.fromEpochMillis(created.asLong()))   // D9: epoch stored as a number
                : ImportDates.parse(created.asString());
        order.customerName = node.path("customer").path("name").asString();
        order.customerPhone = node.path("customer").path("phone").asString();

        if (!stores.contains(order.store)) {
            return "unknown store '" + order.store + "'";
        }
        if (!users.contains(order.placedBy)) {
            return "unknown user '" + order.placedBy + "'";
        }
        if (!STATUSES.contains(order.status)) {
            return "unknown status '" + order.status + "'";
        }
        if (createdAt.isEmpty()) {
            return "created_at '" + created + "' matches no known date format (D9)";
        }
        if (order.customerName.isBlank() || order.customerPhone.isBlank()) {
            return "customer name and phone are required";
        }
        if (!node.path("total_amount").isNumber() || node.path("lines").size() == 0) {
            return "total_amount and at least one line are required";
        }
        order.createdAt = createdAt.get();
        order.fileTotal = node.path("total_amount").decimalValue();

        for (int i = 0; i < node.path("lines").size(); i++) {
            JsonNode line = node.path("lines").get(i);
            String rawSku = line.path("sku").asString();
            BigDecimal price = line.path("unit_price").isNumber() ? line.path("unit_price").decimalValue() : null;
            if (!line.path("qty").isInt()) {
                return "line " + (i + 1) + ": qty must be a whole number";
            }
            order.rawLines.add(new RawLine(rawSku, skus.forOrderLine(rawSku, price), line.path("qty").asInt(), price));
        }
        return null;
    }

    /** Every recorded sale per product: the evidence D11 uses to fill a missing price. */
    private static Map<String, List<Sale>> salesHistory(List<Order> orders) {
        Map<String, List<Sale>> history = new HashMap<>();
        for (Order order : orders) {
            for (RawLine line : order.rawLines) {
                if (line.productSku() != null && line.price() != null && line.qty() > 0) {
                    history.computeIfAbsent(line.productSku(), k -> new ArrayList<>()).add(new Sale(order.createdAt, line.price()));
                }
            }
        }
        return history;
    }

    /** Applies D10-D14 to one order. Returns a reason to reject it, or null. */
    private static String decide(Order order, Map<String, List<Sale>> history, Map<String, BigDecimal> cataloguePrices) {
        boolean priceFilled = false;
        boolean needsReview = false;
        BigDecimal pricedSum = order.rawLines.stream().filter(l -> l.price() != null)
                .map(l -> l.price().multiply(BigDecimal.valueOf(l.qty()))).reduce(BigDecimal.ZERO, BigDecimal::add);
        long unpriced = order.rawLines.stream().filter(l -> l.price() == null).count();

        List<Line> resolved = new ArrayList<>();
        for (RawLine line : order.rawLines) {
            String sku = line.productSku();
            if (line.match().storedSku().isEmpty()) {   // D12
                order.notes.add(new Note("D12", Outcome.INFO,
                        "Unknown product '" + line.rawSku() + "' kept as 'Unknown product'; counted in revenue, no review"));
            } else if (!line.rawSku().equals(sku)) {   // D13
                order.notes.add(new Note("D13", Outcome.CORRECTED,
                        "SKU '" + line.rawSku() + "' matched to '" + sku + "' (D1 step " + line.match().step() + ")"));
            }
            if (line.match().flagged()) {
                order.notes.add(new Note("D1", Outcome.FLAGGED,
                        "SKU '" + line.rawSku() + "' could not be told apart from its variant; clean product used"));
            }

            BigDecimal price = line.price();
            String source = "RECORDED";
            if (price == null) {   // D11: fill from evidence, strongest first
                Optional<BigDecimal> fromHistory = sku == null ? Optional.empty()
                        : historyPrice(history.getOrDefault(sku, List.of()), order.createdAt, cataloguePrices.get(sku));
                Optional<BigDecimal> fromBill = unpriced == 1 && order.fileTotal.compareTo(pricedSum) > 0
                        ? exactDivision(order.fileTotal.subtract(pricedSum), line.qty()) : Optional.empty();
                if (fromBill.isPresent() && fromHistory.isPresent() && fromBill.get().compareTo(fromHistory.get()) == 0) {
                    price = fromBill.get();
                    source = "BILL_TOTAL";
                } else if (fromBill.isEmpty() && fromHistory.isPresent()) {
                    price = fromHistory.get();
                    source = "SALES_HISTORY";
                }
                if (price != null) {
                    priceFilled = true;
                    order.notes.add(new Note("D11", Outcome.CORRECTED, "Missing price for '" + line.rawSku()
                            + "' filled as " + price.toPlainString() + " from " + ("BILL_TOTAL".equals(source) ? "the bill total" : "sales history")));
                } else {
                    price = BigDecimal.ZERO;
                    source = "ADMIN";
                    needsReview = true;
                    order.notes.add(new Note("D11", Outcome.FLAGGED, "Missing price for '" + line.rawSku()
                            + "' could not be proven from evidence; sent to admin review"));
                }
            }
            if (line.qty() <= 0) {   // D10
                needsReview = true;
                order.notes.add(new Note("D10", Outcome.FLAGGED, "Line '" + line.rawSku() + "' has quantity " + line.qty()
                        + "; order kept as received and excluded from revenue until an admin corrects or rejects it"));
            }
            resolved.add(new Line(line.rawSku(), sku, line.qty(), price, source));
        }

        // D14: the same product twice at the same price becomes one line with the quantities added
        Map<String, Line> merged = new LinkedHashMap<>();
        for (Line line : resolved) {
            String key = line.productSku() != null ? line.productSku() : line.rawSku();
            Line earlier = merged.get(key);
            if (earlier == null) {
                merged.put(key, line);
            } else if (earlier.price().compareTo(line.price()) == 0) {
                merged.put(key, new Line(earlier.rawSku(), earlier.productSku(), earlier.qty() + line.qty(), earlier.price(), earlier.priceSource()));
                order.notes.add(new Note("D14", Outcome.CORRECTED, "'" + key + "' listed twice at " + line.price().toPlainString()
                        + "; merged into one line of quantity " + (earlier.qty() + line.qty())));
            } else {
                return "'" + key + "' appears twice at different prices; never averaged, needs review (D14)";
            }
        }
        order.lines = List.copyOf(merged.values());
        order.total = priceFilled
                ? order.lines.stream().map(Line::total).reduce(BigDecimal.ZERO, BigDecimal::add)
                : order.fileTotal;
        if (needsReview) {
            order.reviewStatus = "NEEDS_REVIEW";
            order.reviewNote = "Imported with data that needs an admin decision (see import issues)";
        }
        return null;
    }

    /** D11 step 2: every sale of the product within +/-30 days at one single price that equals the catalogue price. */
    private static Optional<BigDecimal> historyPrice(List<Sale> sales, OffsetDateTime at, BigDecimal cataloguePrice) {
        Set<BigDecimal> prices = sales.stream()
                .filter(s -> Duration.between(s.at(), at).abs().compareTo(HISTORY_WINDOW) <= 0)
                .map(s -> s.price().stripTrailingZeros())
                .collect(Collectors.toCollection(TreeSet::new));
        if (prices.size() == 1 && cataloguePrice != null && prices.iterator().next().compareTo(cataloguePrice) == 0) {
            return Optional.of(cataloguePrice);
        }
        return Optional.empty();
    }

    private static Optional<BigDecimal> exactDivision(BigDecimal amount, int qty) {
        if (qty <= 0) {
            return Optional.empty();
        }
        BigDecimal[] parts = amount.divideAndRemainder(BigDecimal.valueOf(qty));
        return parts[1].signum() == 0 ? Optional.of(parts[0].setScale(2, RoundingMode.UNNECESSARY)) : Optional.empty();
    }

    // --- writing -----------------------------------------------------------

    private Mono<Void> write(long runId, List<Order> orders) {
        List<Object[]> orderRows = new ArrayList<>();
        List<Object[]> lineRows = new ArrayList<>();
        List<Object[]> rawRows = new ArrayList<>();
        List<Mono<Void>> notes = new ArrayList<>();
        for (Order o : orders) {
            orderRows.add(new Object[] {o.id, o.store, o.placedBy, o.status, o.customerName, o.customerPhone, o.total,
                    o.createdAt, o.reviewStatus, o.reviewNote});
            for (Line l : o.lines) {
                lineRows.add(new Object[] {o.id, l.rawSku(), l.productSku(), l.qty(), l.price(), l.priceSource()});
            }
            rawRows.add(new Object[] {runId, ORDERS_FILE, o.position, o.id, Json.of(o.raw.toString())});
            for (Note n : o.notes) {
                notes.add(log.issue(runId, n.decision(), n.outcome(), "ORDER", o.id, n.message()));
            }
        }
        return BatchInsert.run(db, """
                        INSERT INTO customer_orders (id, store_id, placed_by, status, customer_name, customer_phone,
                                                     total_amount, created_at, source, review_status, review_note)
                        VALUES ($1, $2, $3, $4, $5, $6, $7, $8, 'IMPORT', $9, $10)
                        """,
                        new Class<?>[] {String.class, String.class, String.class, String.class, String.class, String.class,
                                BigDecimal.class, OffsetDateTime.class, String.class, String.class},
                        orderRows)
                .then(BatchInsert.run(db, """
                                INSERT INTO order_lines (order_id, sku_text, product_sku, qty, unit_price, price_source, imported)
                                VALUES ($1, $2, $3, $4, $5, $6, true)
                                """,
                        new Class<?>[] {String.class, String.class, String.class, Integer.class, BigDecimal.class, String.class},
                        lineRows))
                .then(BatchInsert.run(db, """
                                INSERT INTO raw_import_records (run_id, source_file, position, natural_key, payload)
                                VALUES ($1, $2, $3, $4, $5)
                                """,
                        new Class<?>[] {Long.class, String.class, Integer.class, String.class, Json.class},
                        rawRows))
                .then(Flux.concat(notes).then());
    }

    private Mono<Set<String>> ids(String sql) {
        return db.sql(sql).map((row, meta) -> row.get(0, String.class)).all().collect(Collectors.toCollection(HashSet::new));
    }
}
