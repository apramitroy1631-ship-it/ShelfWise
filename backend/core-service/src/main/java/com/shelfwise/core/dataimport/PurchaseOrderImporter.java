package com.shelfwise.core.dataimport;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 * Imports purchase_orders.json: decisions D17-D21 and D31.
 * Past approvals and receipts are kept as they are (the past is not rewritten); rule breaks are flagged,
 * and the user's progress rule decides which open POs need re-approval before more goods are received.
 * Received quantities are already reflected in inventory.csv, so they never add stock again (D17, D29).
 */
@Component
class PurchaseOrderImporter {

    static final String PURCHASE_ORDERS_FILE = "purchase_orders.json";

    /** The ₹50,000 rule from the brief; the live system reads it from remote config. */
    static final BigDecimal SECOND_APPROVAL_ABOVE = new BigDecimal("50000");

    private static final Set<String> STATUSES = Set.of("DRAFT", "SUBMITTED", "APPROVED", "PARTIALLY_RECEIVED", "RECEIVED", "REJECTED");
    private static final Set<String> RECEIVING_STATUSES = Set.of("APPROVED", "PARTIALLY_RECEIVED", "RECEIVED");

    private enum Counted { ADDED, SKIPPED, REJECTED }

    private record Approval(String userId, String decision, OffsetDateTime decidedAt, int filePosition) {
    }

    private record PoLine(String sku, int ordered, BigDecimal unitCost, int received) {
    }

    private final DatabaseClient db;
    private final ImportLog log;
    private final ObjectMapper json;

    PurchaseOrderImporter(DatabaseClient db, ImportLog log, ObjectMapper json) {
        this.db = db;
        this.log = log;
        this.json = json;
    }

    Mono<FileResult> importPurchaseOrders(long runId, Path file, SkuResolver skus) {
        Mono<JsonNode> records = Mono.fromCallable(() -> json.readTree(Files.readString(file)))
                .subscribeOn(Schedulers.boundedElastic());
        Mono<Map<String, String>> roles = db.sql("SELECT id, role FROM users")
                .map((row, meta) -> Map.entry(row.get("id", String.class), row.get("role", String.class)))
                .all().collectMap(Map.Entry::getKey, Map.Entry::getValue);
        return Mono.zip(records, ids("SELECT id FROM purchase_orders"), ids("SELECT id FROM stores"),
                        ids("SELECT id FROM suppliers"), roles, ids("SELECT id FROM users WHERE NOT active"))
                .flatMapMany(t -> {
                    JsonNode root = t.getT1();
                    Set<String> seen = new HashSet<>(t.getT2());
                    List<Mono<Counted>> work = new ArrayList<>();
                    for (int i = 0; i < root.size(); i++) {
                        JsonNode node = root.get(i);
                        String id = node.path("po_id").asString();
                        if (!seen.add(id)) {
                            work.add(Mono.just(Counted.SKIPPED));
                            continue;
                        }
                        work.add(importOne(runId, i, node, skus, t.getT3(), t.getT4(), t.getT5(), t.getT6()));
                    }
                    return Flux.concat(work);
                })
                .reduce(FileResult.of(PURCHASE_ORDERS_FILE), (result, counted) -> switch (counted) {
                    case ADDED -> result.plusAdded();
                    case SKIPPED -> result.plusSkipped();
                    case REJECTED -> result.plusRejected();
                });
    }

    private Mono<Counted> importOne(long runId, int position, JsonNode node, SkuResolver skus, Set<String> stores,
                                    Set<String> suppliers, Map<String, String> roles, Set<String> inactiveUsers) {
        String id = node.path("po_id").asString();
        String store = node.path("store_id").asString();
        String supplier = node.path("supplier_id").asString();
        String createdBy = node.path("created_by").asString();
        String fileStatus = node.path("status").asString();
        Optional<OffsetDateTime> createdAt = ImportDates.parse(node.path("created_at").asString());

        String problem = !stores.contains(store) ? "unknown store '" + store + "'"
                : !suppliers.contains(supplier) ? "unknown supplier '" + supplier + "'"
                : !roles.containsKey(createdBy) ? "unknown user '" + createdBy + "'"
                : !STATUSES.contains(fileStatus) ? "unknown status '" + fileStatus + "'"
                : createdAt.isEmpty() ? "created_at matches no known date format"
                : node.path("lines").size() == 0 ? "a purchase order needs at least one line"
                : null;

        List<PoLine> lines = new ArrayList<>();
        for (int i = 0; problem == null && i < node.path("lines").size(); i++) {
            JsonNode line = node.path("lines").get(i);
            Optional<String> sku = skus.forStockOrPurchaseRow(line.path("sku").asString()).storedSku();
            int ordered = line.path("qty_ordered").asInt();
            int received = line.path("qty_received").asInt();
            if (sku.isEmpty()) {
                problem = "line " + (i + 1) + ": unknown product '" + line.path("sku").asString() + "'";
            } else if (ordered <= 0 || received < 0 || received > ordered) {
                problem = "line " + (i + 1) + ": quantities must satisfy 0 <= received <= ordered, ordered > 0";
            } else {
                lines.add(new PoLine(sku.get(), ordered, line.path("unit_cost").decimalValue(), received));
            }
        }
        if (problem != null) {
            return log.issue(runId, "VALIDATION", Outcome.REJECTED, "PURCHASE_ORDER", id, "Array index " + position + ": " + problem)
                    .thenReturn(Counted.REJECTED);
        }

        List<Mono<Void>> notes = new ArrayList<>();
        BigDecimal total = node.path("total_amount").decimalValue();

        // D21: receiving statuses follow the received quantities, never the label
        int ordered = lines.stream().mapToInt(PoLine::ordered).sum();
        int received = lines.stream().mapToInt(PoLine::received).sum();
        String status = fileStatus;
        if (RECEIVING_STATUSES.contains(fileStatus)) {
            status = received == 0 ? "APPROVED" : received == ordered ? "RECEIVED" : "PARTIALLY_RECEIVED";
            if (!status.equals(fileStatus)) {
                notes.add(log.issue(runId, "D21", Outcome.CORRECTED, "PURCHASE_ORDER", id,
                        "Status '" + fileStatus + "' contradicts the received quantities (" + received + " of " + ordered
                                + "); imported as " + status));
            }
        }

        // D31: approval order comes from the time, never the list position (ties keep file order)
        List<Approval> approvals = new ArrayList<>();
        for (int i = 0; i < node.path("approvals").size(); i++) {
            JsonNode a = node.path("approvals").get(i);
            approvals.add(new Approval(a.path("user_id").asString(), a.path("decision").asString(),
                    ImportDates.parse(a.path("decided_at").asString()).orElseThrow(), i));
        }
        approvals.sort(Comparator.comparing(Approval::decidedAt).thenComparing(Approval::filePosition));
        if (approvals.size() == 2 && approvals.get(0).decidedAt().isEqual(approvals.get(1).decidedAt())) {
            notes.add(log.issue(runId, "D31", Outcome.INFO, "PURCHASE_ORDER", id,
                    "Both approvals at the same second; both valid (two different finance approvers)"));
        }

        // D18, D19: only finance approvers count, and never the PO's own creator; D20: inactive approvers stay valid
        Map<Approval, Boolean> counts = new HashMap<>();
        Map<Approval, Integer> steps = new HashMap<>();
        int nextStep = 1;
        boolean selfApproval = false;
        boolean nonFinanceApproval = false;
        for (Approval a : approvals) {
            boolean isFinance = "FINANCE_APPROVER".equals(roles.get(a.userId()));
            boolean isCreator = a.userId().equals(createdBy);
            selfApproval |= isCreator;
            nonFinanceApproval |= !isFinance;
            boolean valid = isFinance && !isCreator;
            counts.put(a, valid);
            if (valid && "APPROVED".equals(a.decision()) && nextStep <= 2) {
                steps.put(a, nextStep++);
            }
            if (inactiveUsers.contains(a.userId())) {
                notes.add(log.issue(runId, "D20", Outcome.INFO, "PURCHASE_ORDER", id,
                        "Approved by " + a.userId() + ", now inactive; the approval stays valid (role was correct at the time)"));
            }
        }

        // D17-D19 and the user's progress rule
        String complianceFlag = null;
        boolean requiresReapproval = false;
        long validApprovals = steps.size();
        long needed = total.compareTo(SECOND_APPROVAL_ABOVE) > 0 ? 2 : 1;
        if (RECEIVING_STATUSES.contains(status) && validApprovals < needed) {
            complianceFlag = selfApproval ? "SELF_APPROVAL" : nonFinanceApproval ? "INVALID_APPROVER" : "SINGLE_APPROVAL";
            String decision = selfApproval ? "D19" : nonFinanceApproval ? "D18" : "D17";
            String progress;
            if (received == ordered) {
                progress = "fully received: kept as history";
            } else if (received == 0) {
                requiresReapproval = true;
                progress = "nothing received: needs re-approval before any goods";
            } else if (selfApproval) {
                requiresReapproval = true;
                progress = "partly received but self-approved: needs re-approval before more goods";
            } else {
                progress = "partly received: allowed to complete";
            }
            notes.add(log.issue(runId, decision, Outcome.FLAGGED, "PURCHASE_ORDER", id,
                    complianceFlag + ": " + validApprovals + " valid approval(s), " + needed + " needed; " + progress));
        }

        DatabaseClient.GenericExecuteSpec insertPo = db.sql("""
                        INSERT INTO purchase_orders (id, store_id, supplier_id, created_by, status, total_amount, created_at,
                                                     source, compliance_flag, requires_reapproval)
                        VALUES (:id, :store, :supplier, :createdBy, :status, :total, :createdAt, 'IMPORT', :flag, :reapprove)
                        """)
                .bind("id", id)
                .bind("store", store)
                .bind("supplier", supplier)
                .bind("createdBy", createdBy)
                .bind("status", status)
                .bind("total", total)
                .bind("createdAt", createdAt.get())
                .bind("reapprove", requiresReapproval);
        insertPo = complianceFlag == null ? insertPo.bindNull("flag", String.class) : insertPo.bind("flag", complianceFlag);

        Mono<Void> insertLines = Flux.fromIterable(lines).concatMap(l -> db.sql("""
                        INSERT INTO po_lines (po_id, sku, qty_ordered, unit_cost, qty_received)
                        VALUES (:po, :sku, :ordered, :cost, :received)
                        """)
                .bind("po", id).bind("sku", l.sku()).bind("ordered", l.ordered())
                .bind("cost", l.unitCost()).bind("received", l.received())
                .then()).then();

        Mono<Void> insertApprovals = Flux.fromIterable(approvals).concatMap(a -> {
            DatabaseClient.GenericExecuteSpec spec = db.sql("""
                            INSERT INTO po_approvals (po_id, approver_id, decision, decided_at, counts, step)
                            VALUES (:po, :approver, :decision, :decidedAt, :counts, :step)
                            """)
                    .bind("po", id).bind("approver", a.userId()).bind("decision", a.decision())
                    .bind("decidedAt", a.decidedAt()).bind("counts", counts.get(a));
            Integer step = steps.get(a);
            return (step == null ? spec.bindNull("step", Short.class) : spec.bind("step", step.shortValue())).then();
        }).then();

        Mono<Void> raw = db.sql("""
                        INSERT INTO raw_import_records (run_id, source_file, position, natural_key, payload)
                        VALUES (:runId, :file, :position, :key, :payload)
                        """)
                .bind("runId", runId).bind("file", PURCHASE_ORDERS_FILE).bind("position", position)
                .bind("key", id).bind("payload", Json.of(node.toString()))
                .then();

        return insertPo.then().then(insertLines).then(insertApprovals).then(raw)
                .then(Flux.concat(notes).then())
                .thenReturn(Counted.ADDED);
    }

    private Mono<Set<String>> ids(String sql) {
        return db.sql(sql).map((row, meta) -> row.get(0, String.class)).all().collect(Collectors.toCollection(HashSet::new));
    }
}
