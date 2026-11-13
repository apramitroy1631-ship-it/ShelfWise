package com.shelfwise.notification;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

/**
 * The backend handler the brief asks for: it receives every event from the core service and updates the
 * grouped notifications (D7). One event = one transaction: the "already handled" mark and the change are
 * saved together, so a repeated event (at-least-once delivery) changes nothing.
 * <ul>
 *   <li>{@code StockHealthChanged}: a store's current "out of stock" and "running low" counts, for its managers.</li>
 *   <li>{@code ApprovalQueueChanged}: POs waiting for finance, and those waiting for a second approval.</li>
 *   <li>{@code PurchaseOrderDecided}: one notification per final decision for the store's managers (not grouped).</li>
 * </ul>
 * Other event types are recorded as handled and otherwise ignored.
 */
@Component
public class NotificationHandler {

    static final String FINANCE = "ROLE:FINANCE_APPROVER";
    static final String ADMIN = "ROLE:ADMIN";

    private final DatabaseClient db;
    private final TransactionalOperator transaction;

    public NotificationHandler(DatabaseClient db, ReactiveTransactionManager transactionManager) {
        this.db = db;
        this.transaction = TransactionalOperator.create(transactionManager);
    }

    /** Returns true if the event changed something, false if it was a repeat. */
    public Mono<Boolean> handle(JsonNode event) {
        UUID id = UUID.fromString(event.path("id").asString());
        String type = event.path("type").asString();
        return db.sql("INSERT INTO notifications.processed_events (event_id, event_type) VALUES (:id, :type) ON CONFLICT DO NOTHING")
                .bind("id", id).bind("type", type)
                .fetch().rowsUpdated()
                .flatMap(inserted -> inserted == 0 ? Mono.just(false) : apply(type, event).thenReturn(true))
                .as(transaction::transactional);
    }

    private Mono<Void> apply(String type, JsonNode event) {
        JsonNode p = event.path("payload");
        return switch (type) {
            case "StockHealthChanged" -> {
                String audience = "STORE:" + p.path("storeId").asString();
                OffsetDateTime asOf = OffsetDateTime.parse(p.path("asOf").asString());
                yield group(audience, "OUT_OF_STOCK", p.path("outOfStock"), asOf)
                        .then(group(audience, "RUNNING_LOW", p.path("runningLow"), asOf));
            }
            case "ApprovalQueueChanged" -> {
                OffsetDateTime asOf = OffsetDateTime.parse(p.path("asOf").asString());
                yield group(FINANCE, "NEEDS_APPROVAL", p.path("needsApproval"), asOf)
                        .then(group(FINANCE, "NEEDS_SECOND_APPROVAL", p.path("needsSecondApproval"), asOf));
            }
            case "StuckWorkChanged" -> {                  // D32 orders, D22 POs
                String audience = "STORE:" + p.path("storeId").asString();
                OffsetDateTime asOf = OffsetDateTime.parse(p.path("asOf").asString());
                yield group(audience, "STUCK_ORDERS", p.path("stuckOrders"), asOf)
                        .then(group(audience, "STUCK_POS", p.path("stuckPurchaseOrders"), asOf));
            }
            case "HeadOfficeChanged" -> {                 // D10 review queue; stock health across all stores (D7)
                OffsetDateTime asOf = OffsetDateTime.parse(p.path("asOf").asString());
                yield group(ADMIN, "NEEDS_REVIEW", p.path("needsReview"), asOf)
                        .then(group(ADMIN, "OUT_OF_STOCK", p.path("outOfStock"), asOf))
                        .then(group(ADMIN, "RUNNING_LOW", p.path("runningLow"), asOf));
            }
            case "PurchaseOrderDecided" -> decision(p, OffsetDateTime.parse(event.path("occurredAt").asString()));
            default -> Mono.empty();
        };
    }

    /**
     * Sets a group to the CURRENT count, only if this state is newer than the one stored (messages may arrive late).
     * It becomes unread again (moves to the top) only when the count goes up (D7). "Became new" is the time this
     * service applies the change, not when the core computed it: otherwise someone who opened the bell while the
     * message was on its way would have the increase marked as already seen.
     */
    private Mono<Void> group(String audience, String kind, JsonNode g, OffsetDateTime asOf) {
        DatabaseClient.GenericExecuteSpec spec = db.sql("""
                        INSERT INTO notifications.notifications AS n
                               (audience, kind, group_key, item_count, latest, oldest_since, as_of, last_increase_at)
                        VALUES (:audience, :kind, :kind, :count, :latest, :oldest, :asOf, now())
                        ON CONFLICT (audience, group_key) DO UPDATE SET
                            item_count = EXCLUDED.item_count,
                            latest = EXCLUDED.latest,
                            oldest_since = EXCLUDED.oldest_since,
                            as_of = EXCLUDED.as_of,
                            last_increase_at = CASE WHEN EXCLUDED.item_count > n.item_count THEN now() ELSE n.last_increase_at END,
                            updated_at = now()
                        WHERE n.as_of < EXCLUDED.as_of
                        """)
                .bind("audience", audience).bind("kind", kind).bind("count", g.path("count").asInt()).bind("asOf", asOf);
        spec = bindText(spec, "latest", text(g.path("latest")));
        String oldest = text(g.path("oldestSince"));
        spec = oldest == null ? spec.bindNull("oldest", OffsetDateTime.class) : spec.bind("oldest", OffsetDateTime.parse(oldest));
        return spec.then();
    }

    /** Only final outcomes reach the store: approved (incl. re-approved), or rejected. A first of two approvals doesn't. */
    private Mono<Void> decision(JsonNode p, OffsetDateTime at) {
        String poId = p.path("poId").asString();
        boolean rejected = "REJECTED".equals(p.path("decision").asString());
        boolean fullyApproved = p.path("validApprovals").asInt() >= p.path("approvalsNeeded").asInt();
        if (!rejected && !fullyApproved) {
            return Mono.empty();
        }
        String reason = text(p.path("reason"));
        String message;
        if (rejected) {
            message = poId + ("CLOSED".equals(p.path("status").asString()) ? " was rejected on re-approval and closed" : " was rejected")
                    + (reason == null || reason.isBlank() ? "" : ": " + reason);
        } else {
            message = poId + (p.path("reapproval").asBoolean() ? " was re-approved; goods can be received" : " was approved");
        }
        return db.sql("""
                        INSERT INTO notifications.notifications AS n
                               (audience, kind, group_key, message, ref, as_of, last_increase_at)
                        VALUES (:audience, 'PO_DECISION', :key, :message, :ref, :at, now())
                        ON CONFLICT (audience, group_key) DO UPDATE SET
                            message = EXCLUDED.message, as_of = EXCLUDED.as_of, last_increase_at = now(), updated_at = now()
                        WHERE n.as_of < EXCLUDED.as_of
                        """)
                .bind("audience", "STORE:" + p.path("storeId").asString())
                .bind("key", "PO_DECISION:" + poId)
                .bind("message", message)
                .bind("ref", poId)
                .bind("at", at)
                .then();
    }

    private static DatabaseClient.GenericExecuteSpec bindText(DatabaseClient.GenericExecuteSpec spec, String name, String value) {
        return value == null ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }

    private static String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asString();
    }
}
