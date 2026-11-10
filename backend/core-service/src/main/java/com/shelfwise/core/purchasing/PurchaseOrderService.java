package com.shelfwise.core.purchasing;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ResponseStatusException;

import com.shelfwise.core.audit.AuditLog;
import com.shelfwise.core.auth.StoreAccess;
import com.shelfwise.core.auth.UserAccount;
import com.shelfwise.core.catalogue.CatalogueDtos.Page;
import com.shelfwise.core.catalogue.Product;
import com.shelfwise.core.catalogue.ProductRepository;
import com.shelfwise.core.config.AppSettings;
import com.shelfwise.core.inventory.InventoryRepository;
import com.shelfwise.core.outbox.Outbox;
import com.shelfwise.core.purchasing.PurchaseOrderDtos.CreateRequest;
import com.shelfwise.core.purchasing.PurchaseOrderDtos.DecisionRequest;
import com.shelfwise.core.purchasing.PurchaseOrderDtos.Detail;
import com.shelfwise.core.purchasing.PurchaseOrderDtos.LineRequest;
import com.shelfwise.core.purchasing.PurchaseOrderDtos.ReceiveRequest;
import com.shelfwise.core.purchasing.PurchaseOrderDtos.Suggestion;
import com.shelfwise.core.purchasing.PurchaseOrderDtos.Summary;
import com.shelfwise.core.purchasing.PurchaseOrderRepository.PoRow;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Purchase orders (M19): a store manager drafts and submits, finance approves or rejects, the store receives
 * the goods in one or more deliveries.
 * <ul>
 *   <li>Above the remote-config limit (default ₹50,000) two DIFFERENT finance approvers are needed; never the
 *       PO's creator (D19), never the same person twice (D31).</li>
 *   <li>Every change first "claims" the PO by its version, so two people acting at once can't both succeed on
 *       stale data; the database's unique rules on approvals are the second line of defence (D31).</li>
 *   <li>Imported POs flagged by D17-D19 that still need re-approval are approved through the same decision
 *       call; until then no goods can be received.</li>
 *   <li>Receiving status follows the quantities (D21). Each decision writes an outbox event in the same
 *       transaction, for the notification service.</li>
 * </ul>
 */
@Service
public class PurchaseOrderService {

    static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> RECEIVING = Set.of("APPROVED", "PARTIALLY_RECEIVED");

    private final PurchaseOrderRepository pos;
    private final ProductRepository products;
    private final InventoryRepository inventory;
    private final AppSettings settings;
    private final AuditLog audit;
    private final Outbox outbox;
    private final TransactionalOperator transaction;

    public PurchaseOrderService(PurchaseOrderRepository pos, ProductRepository products, InventoryRepository inventory,
                                AppSettings settings, AuditLog audit, Outbox outbox, ReactiveTransactionManager transactionManager) {
        this.pos = pos;
        this.products = products;
        this.inventory = inventory;
        this.settings = settings;
        this.audit = audit;
        this.outbox = outbox;
        this.transaction = TransactionalOperator.create(transactionManager);
    }

    // --- reading -----------------------------------------------------------

    public Mono<Page<Summary>> search(UserAccount user, String storeId, String status, String supplierId, String text,
                                      int page, int size) {
        String store = StoreAccess.readableStore(user, storeId);
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            return Mono.error(badRequest("page must be 0 or more and size between 1 and " + MAX_PAGE_SIZE));
        }
        var search = new PurchaseOrderRepository.Search(store, status, supplierId, text, page, size);
        return rules().flatMap(rules -> pos.search(search)
                .map(po -> summary(po, rules))
                .collectList()
                .zipWith(pos.count(search))
                .map(t -> new Page<>(t.getT1(), page, size, t.getT2(), (t.getT2() + size - 1) / size)));
    }

    public Mono<Detail> get(UserAccount user, String id) {
        return load(id).flatMap(po -> {
            StoreAccess.readableStore(user, po.storeId());
            return detail(po);
        });
    }

    /** Low-stock items of one store, grouped by supplier, with a suggested quantity (brief: draft from low stock). */
    public Flux<Suggestion> suggestions(UserAccount user, String storeId) {
        String store = StoreAccess.readableStore(user, storeId);
        if (store == null) {
            return Flux.error(badRequest("Choose a store (storeId)"));
        }
        return pos.lowStockSuggestions(store);
    }

    // --- draft and submit --------------------------------------------------

    /**
     * A store manager drafts a PO for their own store from ONE supplier (D24). Every product must exist, be
     * active (D3) and come from that supplier. Unit cost is the catalogue cost at this moment (snapshot, like D15).
     */
    public Mono<Detail> create(UserAccount user, CreateRequest request) {
        if (user.role() != UserAccount.Role.STORE_MANAGER) {
            return Mono.error(forbidden("Only store managers draft purchase orders"));
        }
        Map<String, Integer> quantities = new LinkedHashMap<>();                 // D14: same product twice = one line
        for (LineRequest line : request.lines()) {
            quantities.merge(line.sku(), line.qty(), Integer::sum);
        }
        String supplierId = request.supplierId().strip();
        return products.supplierExists(supplierId)
                .flatMap(exists -> exists ? Mono.<Void>empty() : Mono.error(badRequest("Unknown supplier '" + supplierId + "'")))
                .thenMany(Flux.fromIterable(quantities.keySet())
                        .concatMap(sku -> products.findBySku(sku)
                                .switchIfEmpty(Mono.error(badRequest("Unknown product '" + sku + "'")))
                                .flatMap(p -> checkOrderable(p, supplierId))))
                .collectList()
                .flatMap(lines -> {
                    BigDecimal total = lines.stream()
                            .map(p -> p.costPriceInr().multiply(BigDecimal.valueOf(quantities.get(p.sku()))))
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    return pos.nextId().flatMap(id -> pos.insertDraft(id, user.storeId(), supplierId, user.id(), total)
                            .thenMany(Flux.fromIterable(lines)
                                    .concatMap(p -> pos.insertLine(id, p.sku(), quantities.get(p.sku()), p.costPriceInr())))
                            .then(load(id)));
                })
                .flatMap(this::detail)
                .as(transaction::transactional);
    }

    private static Mono<Product> checkOrderable(Product p, String supplierId) {
        if (p.archived()) {
            return Mono.error(conflict("'" + p.sku() + "' is archived and can't be reordered (D3)"));
        }
        if (!p.supplierId().equals(supplierId)) {
            return Mono.error(badRequest("'" + p.sku() + "' comes from " + p.supplierId() + ", not " + supplierId
                    + "; one purchase order is for one supplier"));
        }
        return Mono.just(p);
    }

    /** Draft to Submitted, by a manager of that store (or an admin). An expired draft can't be submitted (D22). */
    public Mono<Detail> submit(UserAccount user, String id, long version) {
        return load(id)
                .flatMap(po -> {
                    StoreAccess.checkCanChangeStock(user, po.storeId());
                    if (!"DRAFT".equals(po.status())) {
                        return Mono.error(conflict("Only a draft can be submitted; " + id + " is " + label(po.status())));
                    }
                    return claim(id, version)
                            .then(pos.markSubmitted(id))
                            .then(outbox.add("PURCHASE_ORDER", id, "PurchaseOrderSubmitted", event(po, Map.of(
                                    "submittedBy", user.id()))))
                            .then(load(id));
                })
                .flatMap(this::detail)
                .as(transaction::transactional);
    }

    // --- finance decision --------------------------------------------------

    /**
     * Approve or reject. Used for a submitted PO and for an imported PO flagged "needs re-approval" (D17-D19).
     * An approval counts as step 1 or 2; once the PO has enough (1, or 2 above the limit) it is APPROVED and any
     * re-approval flag is cleared. A rejection needs a reason; a partly received PO whose re-approval is rejected
     * is closed instead (the goods already received stay).
     */
    public Mono<Detail> decide(UserAccount user, String id, DecisionRequest request) {
        if (user.role() != UserAccount.Role.FINANCE_APPROVER) {
            return Mono.error(forbidden("Only finance approvers approve or reject purchase orders"));
        }
        boolean approve = "APPROVED".equals(request.decision());
        String reason = request.reason() == null || request.reason().isBlank() ? null : request.reason().strip();
        if (!approve && reason == null) {
            return Mono.error(badRequest("A rejection needs a reason"));
        }
        return Mono.zip(load(id), rules())
                .flatMap(t -> {
                    PoRow po = t.getT1();
                    Rules rules = t.getT2();
                    boolean reapproval = RECEIVING.contains(po.status()) && po.requiresReapproval();
                    if (!"SUBMITTED".equals(po.status()) && !reapproval) {
                        return Mono.error(conflict(id + " is " + label(po.status()) + "; there is nothing to decide"));
                    }
                    if (po.createdBy().equals(user.id())) {
                        return Mono.error(forbidden("You can't decide on a purchase order you created (D19)"));
                    }
                    int needed = rules.approvalsNeeded(po.totalAmount());
                    return pos.approvals(id)
                            .filter(a -> a.approverId().equals(user.id()))
                            .hasElements()
                            .flatMap(already -> already
                                    ? Mono.error(conflict("You already decided on " + id
                                            + "; a second approval must come from a different person (D31)"))
                                    : claim(id, request.version()))
                            .then(approve ? approve(user, po, needed, reapproval) : reject(user, po, reason))
                            .flatMap(newStatus -> audit.record(user.id(), approve ? "PO_APPROVED" : "PO_REJECTED",
                                            "PURCHASE_ORDER", id, Map.of("status", po.status()), Map.of("status", newStatus), reason)
                                    .then(load(id))
                                    .flatMap(after -> outbox.add("PURCHASE_ORDER", id, "PurchaseOrderDecided", event(after, Map.of(
                                            "decision", request.decision(),
                                            "decidedBy", user.id(),
                                            "reason", reason == null ? "" : reason,
                                            "reapproval", reapproval,
                                            "validApprovals", after.validApprovals(),
                                            "approvalsNeeded", needed)))))
                            .then(load(id));
                })
                .flatMap(this::detail)
                // backstop: the same person twice, or two "2nd approvals" at once, is refused by the database (D31)
                .onErrorMap(DataIntegrityViolationException.class,
                        e -> conflict("Someone else just decided on " + id + "; reload and try again"))
                .as(transaction::transactional);
    }

    /** Returns the status the PO ends up in. */
    private Mono<String> approve(UserAccount user, PoRow po, int needed, boolean reapproval) {
        int step = po.validApprovals() + 1;
        Mono<String> result;
        if (step >= needed) {
            String newStatus = reapproval ? po.status() : "APPROVED";
            result = pos.setStatus(po.id(), newStatus).then(pos.clearReapproval(po.id())).thenReturn(newStatus);
        } else {
            result = Mono.just(po.status());                                    // waiting for the 2nd approver
        }
        return pos.insertApproval(po.id(), user.id(), "APPROVED", Math.min(step, 2)).then(result);
    }

    private Mono<String> reject(UserAccount user, PoRow po, String reason) {
        Mono<Void> record = pos.insertApproval(po.id(), user.id(), "REJECTED", null);
        if ("PARTIALLY_RECEIVED".equals(po.status())) {
            return record.then(pos.close(po.id(), "Re-approval rejected: " + reason, user.id()))
                    .then(pos.clearReapproval(po.id())).thenReturn("CLOSED");
        }
        return record.then(pos.setStatus(po.id(), "REJECTED")).then(pos.clearReapproval(po.id())).thenReturn("REJECTED");
    }

    // --- receiving and closing ---------------------------------------------

    /**
     * Goods arrive, possibly in parts. Each line may not go above what was ordered (also enforced by the
     * database). Stock goes up in the same transaction, with a PO_RECEIPT movement. Status follows (D21).
     */
    public Mono<Detail> receive(UserAccount user, String id, ReceiveRequest request) {
        Map<String, Integer> quantities = new LinkedHashMap<>();
        for (LineRequest line : request.lines()) {
            quantities.merge(line.sku(), line.qty(), Integer::sum);
        }
        return load(id)
                .flatMap(po -> {
                    StoreAccess.checkCanChangeStock(user, po.storeId());
                    if (!RECEIVING.contains(po.status())) {
                        return Mono.error(conflict("Goods can be received only on an approved PO; " + id + " is " + label(po.status())));
                    }
                    if (po.requiresReapproval()) {
                        return Mono.error(conflict(id + " must be re-approved by finance before more goods are received (D17-D19)"));
                    }
                    return claim(id, request.version())
                            .thenMany(Flux.fromIterable(quantities.entrySet())
                                    .concatMap(e -> receiveLine(user, po, e.getKey(), e.getValue())))
                            .then(pos.receivedTotals(id))
                            .flatMap(totals -> {
                                String newStatus = totals[1] >= totals[0] ? "RECEIVED" : "PARTIALLY_RECEIVED";
                                return pos.setStatus(id, newStatus)
                                        .then(outbox.add("PURCHASE_ORDER", id, "PurchaseOrderReceived", event(po, Map.of(
                                                "status", newStatus, "receivedBy", user.id(),
                                                "qtyOrdered", totals[0], "qtyReceived", totals[1]))));
                            })
                            .then(load(id));
                })
                .flatMap(this::detail)
                .as(transaction::transactional);
    }

    private Mono<Void> receiveLine(UserAccount user, PoRow po, String sku, int qty) {
        return pos.receiveOnLine(po.id(), sku, qty)
                .switchIfEmpty(Mono.defer(() -> pos.lines(po.id())
                        .filter(l -> l.sku().equals(sku))
                        .next()
                        .flatMap(l -> Mono.<Integer>error(conflict("'" + sku + "': " + l.qtyReceived() + " of " + l.qtyOrdered()
                                + " already received; " + qty + " more is above what was ordered")))
                        .switchIfEmpty(Mono.error(badRequest("'" + sku + "' is not on " + po.id())))))
                .then(pos.recordReceipt(po.id(), sku, qty, user.id()))
                .then(inventory.receive(po.storeId(), sku, qty))
                .flatMap(after -> inventory.recordMovement(po.storeId(), sku, qty, after, "PO_RECEIPT", null,
                        "PURCHASE_ORDER", po.id(), user.id()))
                .then();
    }

    /** D22: the manager stops expecting the rest of a stuck PO. Goods already received stay. Audited. */
    public Mono<Detail> close(UserAccount user, String id, String reason, long version) {
        return Mono.zip(load(id), rules())
                .flatMap(t -> {
                    PoRow po = t.getT1();
                    StoreAccess.checkCanChangeStock(user, po.storeId());
                    if (!isStuck(po, t.getT2())) {
                        return Mono.error(conflict("Only a stuck PO (approved and waiting more than "
                                + t.getT2().stuckAfterDays() + " days for goods) can be closed"));
                    }
                    return claim(id, version)
                            .then(pos.close(id, reason.strip(), user.id()))
                            .then(audit.record(user.id(), "PO_CLOSED", "PURCHASE_ORDER", id,
                                    Map.of("status", po.status()), Map.of("status", "CLOSED"), reason.strip()))
                            .then(load(id));
                })
                .flatMap(this::detail)
                .as(transaction::transactional);
    }

    // --- helpers -----------------------------------------------------------

    /** The remote-config values these rules depend on, read once per request. */
    record Rules(int secondApprovalAboveInr, int stuckAfterDays) {
        int approvalsNeeded(BigDecimal total) {
            return total.compareTo(BigDecimal.valueOf(secondApprovalAboveInr)) > 0 ? 2 : 1;
        }
    }

    private Mono<Rules> rules() {
        return Mono.zip(settings.integer("purchaseOrders.secondApprovalAboveInr", 50_000),
                        settings.integer("purchaseOrders.stuckAfterDays", 30))
                .map(t -> new Rules(t.getT1(), t.getT2()));
    }

    private Mono<PoRow> load(String id) {
        return pos.find(id).switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "No purchase order '" + id + "'")));
    }

    private Mono<Void> claim(String id, long version) {
        return pos.claim(id, version).flatMap(ok -> ok ? Mono.<Void>empty()
                : Mono.error(conflict(id + " was changed by someone else; reload and try again")));
    }

    private Mono<Detail> detail(PoRow po) {
        return Mono.zip(pos.lines(po.id()).collectList(), pos.approvals(po.id()).collectList(), rules())
                .map(t -> new Detail(summary(po, t.getT3()), po.createdBy(), po.closedReason(), t.getT1(), t.getT2()));
    }

    private static Summary summary(PoRow po, Rules rules) {
        return new Summary(po.id(), po.storeId(), po.supplierId(), po.status(), po.totalAmount(), po.createdAt(),
                po.validApprovals(), rules.approvalsNeeded(po.totalAmount()), po.complianceFlag(), po.requiresReapproval(),
                isStuck(po, rules), po.version());
    }

    /** D22: approved or partly received, and waiting for goods longer than the limit since it was approved. */
    private static boolean isStuck(PoRow po, Rules rules) {
        OffsetDateTime since = po.approvedAt() != null ? po.approvedAt() : po.createdAt();
        return RECEIVING.contains(po.status()) && since.isBefore(OffsetDateTime.now().minus(Duration.ofDays(rules.stuckAfterDays())));
    }

    /** Common event fields; the notification service needs the store and creator to know whom to tell. */
    private static Map<String, Object> event(PoRow po, Map<String, Object> extra) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("poId", po.id());
        payload.put("storeId", po.storeId());
        payload.put("supplierId", po.supplierId());
        payload.put("createdBy", po.createdBy());
        payload.put("totalAmount", po.totalAmount());
        payload.put("status", po.status());
        payload.putAll(extra);
        return payload;
    }

    private static String label(String status) {
        return status.toLowerCase().replace('_', ' ');
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException forbidden(String message) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }
}
