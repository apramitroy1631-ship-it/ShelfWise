package com.shelfwise.core.review;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ResponseStatusException;

import com.shelfwise.core.audit.AuditLog;
import com.shelfwise.core.auth.UserAccount;
import com.shelfwise.core.review.ReviewDtos.CorrectRequest;
import com.shelfwise.core.review.ReviewDtos.LineFix;
import com.shelfwise.core.review.ReviewDtos.Problem;
import com.shelfwise.core.review.ReviewDtos.RejectRequest;
import com.shelfwise.core.review.ReviewDtos.ReviewItem;
import com.shelfwise.core.review.ReviewDtos.ReviewLine;
import com.shelfwise.core.review.ReviewDtos.ReviewList;
import com.shelfwise.core.review.ReviewRepository.LineRow;
import com.shelfwise.core.review.ReviewRepository.OrderRow;

import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The admin review queue (D10). Imported orders whose money can't be trusted are kept exactly as received,
 * flagged, and left out of revenue, counts, top products and the export until an admin decides:
 * <ul>
 *   <li><b>Correct</b>: enter the right quantity (D10) or price (D11) for each flagged line, with a reason.
 *       The total is recalculated and the order joins revenue.</li>
 *   <li><b>Reject</b>: with a reason; the order stays out for good.</li>
 * </ul>
 * It's a review flag, not an order status: the lifecycle is untouched. Stock is never changed (it comes from the
 * inventory count, not from replaying old orders). Every decision is audited with the before-and-after.
 */
@Service
public class ReviewService {

    private final ReviewRepository reviews;
    private final AuditLog audit;
    private final ObjectMapper json;
    private final TransactionalOperator transaction;

    public ReviewService(ReviewRepository reviews, AuditLog audit, ObjectMapper json, ReactiveTransactionManager transactionManager) {
        this.reviews = reviews;
        this.audit = audit;
        this.json = json;
        this.transaction = TransactionalOperator.create(transactionManager);
    }

    // --- reading -------------------------------------------------------------

    /** {@code state}: WAITING (default) or DECIDED. */
    public Mono<ReviewList> list(UserAccount user, String state, String storeId) {
        requireAdmin(user);
        boolean waiting = state == null || state.isBlank() || "WAITING".equalsIgnoreCase(state);
        if (!waiting && !"DECIDED".equalsIgnoreCase(state)) {
            return Mono.error(badRequest("state must be WAITING or DECIDED"));
        }
        String store = storeId == null || storeId.isBlank() ? null : storeId;
        return Mono.zip(reviews.list(waiting, store).collectList().flatMap(this::items), reviews.waitingCount(store))
                .map(t -> new ReviewList(t.getT2(), t.getT1()));
    }

    public Mono<ReviewItem> get(UserAccount user, String orderId) {
        requireAdmin(user);
        return load(orderId).flatMap(o -> items(List.of(o))).map(list -> list.get(0));
    }

    // --- deciding ------------------------------------------------------------

    public Mono<ReviewItem> correct(UserAccount user, String orderId, CorrectRequest request) {
        requireAdmin(user);
        String reason = request.reason().strip();
        return load(orderId)
                .flatMap(order -> {
                    requireWaiting(order);
                    return reviews.lines(new String[] {orderId}).collectList().flatMap(lines -> {
                        Map<String, LineFix> fixes = checkFixes(lines, request.lines());
                        Map<String, Object> before = snapshot(order.totalAmount(), lines);
                        Mono<Void> applyFixes = reactor.core.publisher.Flux.fromIterable(lines)
                                .filter(l -> fixes.containsKey(l.sku()))
                                .concatMap(l -> {
                                    LineFix fix = fixes.get(l.sku());
                                    int qty = fix.qty() != null ? fix.qty() : l.qty();
                                    BigDecimal price = fix.unitPrice() != null ? fix.unitPrice() : l.unitPrice();
                                    return reviews.fixLine(orderId, l.sku(), qty, price);
                                })
                                .then();
                        return claim(order, request.version(), "CORRECTED", reason, user)
                                .then(applyFixes)
                                .then(reviews.recalculateTotal(orderId))
                                .then(reviews.lines(new String[] {orderId}).collectList())
                                .flatMap(after -> reviews.find(orderId).flatMap(saved -> audit.record(user.id(),
                                        "ORDER_REVIEW_CORRECTED", "ORDER", orderId, before,
                                        snapshot(saved.totalAmount(), after), reason)));
                    });
                })
                .then(Mono.defer(() -> get(user, orderId)))
                .as(transaction::transactional);
    }

    public Mono<ReviewItem> reject(UserAccount user, String orderId, RejectRequest request) {
        requireAdmin(user);
        String reason = request.reason().strip();
        return load(orderId)
                .flatMap(order -> {
                    requireWaiting(order);
                    return claim(order, request.version(), "REJECTED", reason, user)
                            .then(audit.record(user.id(), "ORDER_REVIEW_REJECTED", "ORDER", orderId,
                                    Map.of("reviewStatus", "NEEDS_REVIEW"), Map.of("reviewStatus", "REJECTED"), reason));
                })
                .then(Mono.defer(() -> get(user, orderId)))
                .as(transaction::transactional);
    }

    // --- rules ---------------------------------------------------------------

    /**
     * Every flagged line must be fixed, and only flagged lines can be: a NEGATIVE_QTY line needs a quantity and
     * keeps its recorded price (D15); a NO_PRICE line needs a price (and may also correct its quantity).
     */
    private static Map<String, LineFix> checkFixes(List<LineRow> lines, List<LineFix> requested) {
        Map<String, LineRow> bySku = lines.stream().collect(Collectors.toMap(LineRow::sku, l -> l, (a, b) -> a, LinkedHashMap::new));
        Map<String, LineFix> fixes = new HashMap<>();
        for (LineFix fix : requested) {
            LineRow line = bySku.get(fix.sku());
            Problem problem = line == null ? null : problemNow(line);
            if (problem == null) {
                throw badRequest("Only flagged lines can be changed; '" + fix.sku() + "' is not one of them");
            }
            if (fixes.put(fix.sku(), fix) != null) {
                throw badRequest("'" + fix.sku() + "' is listed twice");
            }
            if (problem == Problem.NEGATIVE_QTY && fix.qty() == null) {
                throw badRequest("'" + fix.sku() + "' needs the right quantity (at least 1)");
            }
            if (problem == Problem.NEGATIVE_QTY && fix.unitPrice() != null && fix.unitPrice().compareTo(line.unitPrice()) != 0) {
                throw badRequest("The recorded price of '" + fix.sku() + "' can't be changed (D15); only its quantity");
            }
            if (problem == Problem.NO_PRICE && fix.unitPrice() == null) {
                throw badRequest("'" + fix.sku() + "' needs a price");
            }
            if (problem == Problem.NO_PRICE && fix.qty() == null && line.qty() <= 0) {
                throw badRequest("'" + fix.sku() + "' also needs the right quantity (at least 1)");
            }
        }
        Set<String> missing = bySku.values().stream().filter(l -> problemNow(l) != null).map(LineRow::sku)
                .filter(sku -> !fixes.containsKey(sku)).collect(Collectors.toCollection(java.util.TreeSet::new));
        if (!missing.isEmpty()) {
            throw badRequest("Every flagged line needs a correction; missing: " + String.join(", ", missing));
        }
        return fixes;
    }

    /** A line still waiting for a fix. */
    private static Problem problemNow(LineRow l) {
        if ("ADMIN".equals(l.priceSource()) && l.unitPrice().signum() == 0) {
            return Problem.NO_PRICE;
        }
        return l.qty() <= 0 ? Problem.NEGATIVE_QTY : null;
    }

    /** {@code version} is the one the admin's screen loaded: a decision made on an out-of-date view is refused. */
    private Mono<Void> claim(OrderRow order, long version, String outcome, String reason, UserAccount user) {
        return reviews.decide(order.id(), version, outcome, reason, user.id())
                .flatMap(won -> won ? Mono.<Void>empty() : Mono.error(conflict(
                        order.id() + " was just decided or changed by someone else; reload and try again")));
    }

    private static void requireWaiting(OrderRow order) {
        if (!ReviewRepository.WAITING.equals(order.reviewStatus())) {
            throw conflict(order.id() + " was already " + order.reviewStatus().toLowerCase() + "; nothing left to decide");
        }
    }

    private Mono<OrderRow> load(String orderId) {
        return reviews.find(orderId).switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                "No order '" + orderId + "' in the review queue")));
    }

    // --- building the response ------------------------------------------------

    private Mono<List<ReviewItem>> items(List<OrderRow> orders) {
        if (orders.isEmpty()) {
            return Mono.just(List.of());
        }
        String[] ids = orders.stream().map(OrderRow::id).toArray(String[]::new);
        return Mono.zip(reviews.lines(ids).collectMultimap(LineRow::orderId),
                        reviews.originals(ids).collectMap(r -> r[0], r -> r[1]))
                .map(t -> orders.stream().map(o -> item(o, List.copyOf(t.getT1().getOrDefault(o.id(), List.of())),
                        t.getT2().get(o.id()))).toList());
    }

    private ReviewItem item(OrderRow o, List<LineRow> lines, String beforeJson) {
        Map<String, JsonNode> originalLines = new HashMap<>();
        BigDecimal originalTotal = null;
        if (beforeJson != null) {
            JsonNode before = json.readTree(beforeJson);
            originalTotal = before.path("totalAmount").decimalValue();
            for (JsonNode l : before.path("lines")) {
                originalLines.put(l.path("sku").asString(), l);
            }
        }
        List<ReviewLine> views = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (LineRow l : lines) {
            JsonNode orig = originalLines.get(l.sku());
            int qtyAtImport = orig == null ? l.qty() : orig.path("qty").asInt();
            BigDecimal priceAtImport = orig == null ? l.unitPrice() : orig.path("unitPrice").decimalValue();
            Problem problem = "ADMIN".equals(l.priceSource()) && priceAtImport.signum() == 0 ? Problem.NO_PRICE
                    : qtyAtImport <= 0 ? Problem.NEGATIVE_QTY : null;
            boolean changed = orig != null && (qtyAtImport != l.qty() || priceAtImport.compareTo(l.unitPrice()) != 0);
            views.add(new ReviewLine(l.sku(), l.productSku(), l.productName(), l.qty(), l.unitPrice(), l.lineTotal(), problem,
                    changed ? qtyAtImport : null, changed ? priceAtImport : null));
            if (problem == Problem.NEGATIVE_QTY) {
                problems.add("'" + l.sku() + "' has quantity " + qtyAtImport + " (D10)");
            } else if (problem == Problem.NO_PRICE) {
                problems.add("'" + l.sku() + "' has no price that could be proven (D11)");
            }
        }
        return new ReviewItem(o.id(), o.storeId(), o.status(), o.createdAt(), o.customerName(), o.totalAmount(), originalTotal,
                o.reviewStatus(), o.reviewNote(), o.reviewedBy(), o.reviewedByName(), o.reviewedAt(), o.version(),
                problems, views);
    }

    private static Map<String, Object> snapshot(BigDecimal total, List<LineRow> lines) {
        List<Map<String, Object>> rows = lines.stream()
                .map(l -> Map.<String, Object>of("sku", l.sku(), "qty", l.qty(), "unitPrice", l.unitPrice()))
                .toList();
        return Map.of("totalAmount", total, "lines", rows);
    }

    // --- errors --------------------------------------------------------------

    private static void requireAdmin(UserAccount user) {
        if (user.role() != UserAccount.Role.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only admins review flagged orders");
        }
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
