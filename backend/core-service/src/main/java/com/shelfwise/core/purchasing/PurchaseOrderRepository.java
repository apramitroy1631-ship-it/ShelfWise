package com.shelfwise.core.purchasing;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;

import com.shelfwise.core.purchasing.PurchaseOrderDtos.ApprovalView;
import com.shelfwise.core.purchasing.PurchaseOrderDtos.LineView;
import com.shelfwise.core.purchasing.PurchaseOrderDtos.Suggestion;

import io.r2dbc.spi.Row;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Purchase-order queries and the atomic updates used by approvals and receiving. */
@Repository
public class PurchaseOrderRepository {

    public record PoRow(String id, String storeId, String supplierId, String createdBy, String status, BigDecimal totalAmount,
                        OffsetDateTime createdAt, String complianceFlag, boolean requiresReapproval, String closedReason, long version,
                        int validApprovals, OffsetDateTime approvedAt) {
    }

    public record Search(String storeId, String status, String supplierId, String text, int page, int size) {
    }

    private static final String COLUMNS = """
            id, store_id, supplier_id, created_by, status, total_amount, created_at, compliance_flag, requires_reapproval,
            closed_reason, version,
            (SELECT count(*) FROM po_approvals a WHERE a.po_id = purchase_orders.id AND a.counts AND a.step IS NOT NULL)::int AS valid_approvals,
            (SELECT max(a.decided_at) FROM po_approvals a WHERE a.po_id = purchase_orders.id AND a.counts AND a.step IS NOT NULL) AS approved_at
            """;

    private final DatabaseClient db;

    public PurchaseOrderRepository(DatabaseClient db) {
        this.db = db;
    }

    public Flux<PoRow> search(Search s) {
        Where w = where(s);
        return bindAll(db.sql("SELECT " + COLUMNS + " FROM purchase_orders" + w.sql
                        + " ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset"), w.params)
                .bind("limit", s.size()).bind("offset", (long) s.page() * s.size())
                .map((row, meta) -> toPo(row)).all();
    }

    public Mono<Long> count(Search s) {
        Where w = where(s);
        return bindAll(db.sql("SELECT count(*) FROM purchase_orders" + w.sql), w.params).map((row, meta) -> row.get(0, Long.class)).one();
    }

    public Mono<PoRow> find(String id) {
        return db.sql("SELECT " + COLUMNS + " FROM purchase_orders WHERE id = :id").bind("id", id).map((row, meta) -> toPo(row)).one();
    }

    public Flux<LineView> lines(String poId) {
        return db.sql("""
                        SELECT l.sku, p.name, l.qty_ordered, l.qty_received, l.unit_cost
                        FROM po_lines l JOIN products p ON p.sku = l.sku WHERE l.po_id = :id ORDER BY l.id
                        """)
                .bind("id", poId)
                .map((row, meta) -> {
                    BigDecimal cost = row.get("unit_cost", BigDecimal.class);
                    int ordered = row.get("qty_ordered", Integer.class);
                    return new LineView(row.get("sku", String.class), row.get("name", String.class), ordered,
                            row.get("qty_received", Integer.class), cost, cost.multiply(BigDecimal.valueOf(ordered)));
                })
                .all();
    }

    /** Approvals in time order (D31). */
    public Flux<ApprovalView> approvals(String poId) {
        return db.sql("SELECT approver_id, decision, decided_at, counts, step FROM po_approvals WHERE po_id = :id ORDER BY decided_at, id")
                .bind("id", poId)
                .map((row, meta) -> {
                    Short step = row.get("step", Short.class);
                    return new ApprovalView(row.get("approver_id", String.class), row.get("decision", String.class),
                            row.get("decided_at", OffsetDateTime.class), Boolean.TRUE.equals(row.get("counts", Boolean.class)),
                            step == null ? null : step.intValue());
                })
                .all();
    }

    public Flux<Suggestion> lowStockSuggestions(String storeId) {
        return db.sql("""
                        SELECT i.sku, p.name, p.supplier_id, i.quantity, i.reorder_level, p.cost_price_inr
                        FROM inventory i JOIN products p ON p.sku = i.sku
                        WHERE i.store_id = :store AND i.quantity <= i.reorder_level AND p.status = 'ACTIVE'
                        ORDER BY p.supplier_id, p.name
                        """)
                .bind("store", storeId)
                .map((row, meta) -> {
                    int quantity = row.get("quantity", Integer.class);
                    int reorder = row.get("reorder_level", Integer.class);
                    // bring stock back to twice the reorder level, and order at least one reorder level's worth
                    int suggested = Math.max(reorder, 2 * reorder - quantity);
                    return new Suggestion(row.get("sku", String.class), row.get("name", String.class), row.get("supplier_id", String.class),
                            quantity, reorder, suggested, row.get("cost_price_inr", BigDecimal.class));
                })
                .all();
    }

    public Mono<String> nextId() {
        return db.sql("SELECT 'PO-' || nextval('po_number_seq')").map((row, meta) -> row.get(0, String.class)).one();
    }

    public Mono<Void> insertDraft(String id, String storeId, String supplierId, String createdBy, BigDecimal total) {
        return db.sql("""
                        INSERT INTO purchase_orders (id, store_id, supplier_id, created_by, status, total_amount, created_at, source)
                        VALUES (:id, :store, :supplier, :createdBy, 'DRAFT', :total, now(), 'APP')
                        """)
                .bind("id", id).bind("store", storeId).bind("supplier", supplierId).bind("createdBy", createdBy).bind("total", total)
                .then();
    }

    public Mono<Void> insertLine(String poId, String sku, int qty, BigDecimal unitCost) {
        return db.sql("INSERT INTO po_lines (po_id, sku, qty_ordered, unit_cost) VALUES (:po, :sku, :qty, :cost)")
                .bind("po", poId).bind("sku", sku).bind("qty", qty).bind("cost", unitCost).then();
    }

    /**
     * Claims the PO for one change: bumps the version only if nobody changed it since the caller loaded it.
     * Every approval, receipt and status change starts with this, so two people can't act on stale data (D31).
     */
    public Mono<Boolean> claim(String id, long version) {
        return db.sql("UPDATE purchase_orders SET version = version + 1, updated_at = now() WHERE id = :id AND version = :version")
                .bind("id", id).bind("version", version).fetch().rowsUpdated().map(n -> n == 1);
    }

    public Mono<Void> setStatus(String id, String status) {
        return db.sql("UPDATE purchase_orders SET status = :status WHERE id = :id").bind("id", id).bind("status", status).then();
    }

    public Mono<Void> markSubmitted(String id) {
        return db.sql("UPDATE purchase_orders SET status = 'SUBMITTED', submitted_at = now() WHERE id = :id").bind("id", id).then();
    }

    public Mono<Void> clearReapproval(String id) {
        return db.sql("UPDATE purchase_orders SET requires_reapproval = false WHERE id = :id").bind("id", id).then();
    }

    public Mono<Void> close(String id, String reason, String userId) {
        return db.sql("""
                        UPDATE purchase_orders SET status = 'CLOSED', closed_reason = :reason, closed_by = :user, closed_at = now()
                        WHERE id = :id
                        """)
                .bind("id", id).bind("reason", reason).bind("user", userId).then();
    }

    public Mono<Void> insertApproval(String poId, String approverId, String decision, Integer step) {
        DatabaseClient.GenericExecuteSpec spec = db.sql("""
                        INSERT INTO po_approvals (po_id, approver_id, decision, decided_at, counts, step)
                        VALUES (:po, :approver, :decision, now(), true, :step)
                        """)
                .bind("po", poId).bind("approver", approverId).bind("decision", decision);
        return (step == null ? spec.bindNull("step", Short.class) : spec.bind("step", step.shortValue())).then();
    }

    /** Adds received units to a line only if it doesn't exceed what was ordered. Returns the new received total. */
    public Mono<Integer> receiveOnLine(String poId, String sku, int qty) {
        return db.sql("""
                        UPDATE po_lines SET qty_received = qty_received + :qty
                        WHERE po_id = :po AND sku = :sku AND qty_received + :qty <= qty_ordered
                        RETURNING qty_received
                        """)
                .bind("po", poId).bind("sku", sku).bind("qty", qty)
                .map((row, meta) -> row.get(0, Integer.class)).one();
    }

    public Mono<Void> recordReceipt(String poId, String sku, int qty, String userId) {
        return db.sql("INSERT INTO po_receipts (po_id, sku, qty, received_by) VALUES (:po, :sku, :qty, :user)")
                .bind("po", poId).bind("sku", sku).bind("qty", qty).bind("user", userId).then();
    }

    /** Ordered and received totals, used to derive the receiving status (D21). */
    public Mono<int[]> receivedTotals(String poId) {
        return db.sql("SELECT sum(qty_ordered)::int, sum(qty_received)::int FROM po_lines WHERE po_id = :id")
                .bind("id", poId).map((row, meta) -> new int[] {row.get(0, Integer.class), row.get(1, Integer.class)}).one();
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
        if (s.supplierId() != null && !s.supplierId().isBlank()) {
            conditions.add("supplier_id = :supplier");
            params.put("supplier", s.supplierId());
        }
        if (s.text() != null && !s.text().isBlank()) {
            conditions.add("id ILIKE :text");
            params.put("text", "%" + s.text().strip().replace("%", "").replace("_", "") + "%");
        }
        return new Where(conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions), params);
    }

    private static DatabaseClient.GenericExecuteSpec bindAll(DatabaseClient.GenericExecuteSpec spec, Map<String, Object> params) {
        for (Map.Entry<String, Object> p : params.entrySet()) {
            spec = spec.bind(p.getKey(), p.getValue());
        }
        return spec;
    }

    private static PoRow toPo(Row row) {
        return new PoRow(row.get("id", String.class), row.get("store_id", String.class), row.get("supplier_id", String.class),
                row.get("created_by", String.class), row.get("status", String.class), row.get("total_amount", BigDecimal.class),
                row.get("created_at", OffsetDateTime.class), row.get("compliance_flag", String.class),
                Boolean.TRUE.equals(row.get("requires_reapproval", Boolean.class)), row.get("closed_reason", String.class),
                row.get("version", Long.class), row.get("valid_approvals", Integer.class), row.get("approved_at", OffsetDateTime.class));
    }
}
