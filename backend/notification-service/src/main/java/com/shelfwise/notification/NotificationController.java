package com.shelfwise.notification;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;

/**
 * The bell (DL-12). Who sees what comes from the token: a store manager sees their store's notifications,
 * finance sees the finance ones. Groups at 0 are hidden ("the notification clears at 0", D7).
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    public record Item(long id, String kind, String text, Integer count, String ref, boolean unread, OffsetDateTime at) {
    }

    public record Feed(long unreadCount, List<Item> items) {
    }

    private final DatabaseClient db;

    public NotificationController(DatabaseClient db) {
        this.db = db;
    }

    @GetMapping
    public Mono<Feed> feed(@AuthenticationPrincipal Jwt token) {
        String audience = audience(token);
        return db.sql("""
                        SELECT n.id, n.kind, n.item_count, n.latest, n.oldest_since, n.message, n.ref, n.audience,
                               n.last_increase_at, (r.seen_at IS NULL OR r.seen_at < n.last_increase_at) AS unread
                        FROM notifications.notifications n
                        LEFT JOIN notifications.reads r ON r.notification_id = n.id AND r.user_id = :user
                        WHERE n.audience = :audience AND (n.item_count IS NULL OR n.item_count > 0)
                        ORDER BY n.last_increase_at DESC, n.id DESC
                        LIMIT 50
                        """)
                .bind("user", token.getSubject()).bind("audience", audience)
                .map((row, meta) -> {
                    Integer count = row.get("item_count", Integer.class);
                    return new Item(row.get("id", Long.class), row.get("kind", String.class),
                            text(row.get("kind", String.class), count, row.get("latest", String.class),
                                    row.get("oldest_since", OffsetDateTime.class), row.get("message", String.class),
                                    row.get("audience", String.class)),
                            count, row.get("ref", String.class), Boolean.TRUE.equals(row.get("unread", Boolean.class)),
                            row.get("last_increase_at", OffsetDateTime.class));
                })
                .all()
                .collectList()
                .map(items -> new Feed(items.stream().filter(Item::unread).count(), items));
    }

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> read(@AuthenticationPrincipal Jwt token, @PathVariable long id) {
        return markRead(token, "n.id = :id", id)
                .flatMap(n -> n == 0 ? Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "No notification " + id)) : Mono.empty());
    }

    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> readAll(@AuthenticationPrincipal Jwt token) {
        return markRead(token, "true", null).then();
    }

    /** Only notifications of the caller's own audience can be marked, so ids from another store give 404. */
    private Mono<Long> markRead(Jwt token, String condition, Long id) {
        String sql = "INSERT INTO notifications.reads (user_id, notification_id, seen_at)"
                + " SELECT :user, n.id, now() FROM notifications.notifications n"
                + " WHERE n.audience = :audience AND " + condition
                + " ON CONFLICT (user_id, notification_id) DO UPDATE SET seen_at = now()";
        DatabaseClient.GenericExecuteSpec spec = db.sql(sql).bind("user", token.getSubject()).bind("audience", audience(token));
        return (id == null ? spec : spec.bind("id", id)).fetch().rowsUpdated();
    }

    /** Store managers: their store. Finance: the finance group. Admin groups come with the nightly job (M23). */
    static String audience(Jwt token) {
        String role = token.getClaimAsString("role");
        if ("STORE_MANAGER".equals(role)) {
            String store = token.getClaimAsString("storeId");
            if (store == null) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No store on this account");
            }
            return "STORE:" + store;
        }
        if ("FINANCE_APPROVER".equals(role) || "ADMIN".equals(role)) {
            return "ROLE:" + role;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Unknown role");
    }

    /** The words people see, e.g. "Smart Buy Potato Chips 150g and 39 other items are out of stock in S04". */
    static String text(String kind, Integer count, String latest, OffsetDateTime oldestSince, String message, String audience) {
        String store = audience.startsWith("STORE:") ? audience.substring(6) : "";
        int n = count == null ? 0 : count;
        String others = n <= 1 ? latest + " is" : latest + " and " + (n - 1) + " other item" + (n == 2 ? "" : "s") + " are";
        String waiting = oldestSince == null ? "" : " · oldest waiting " + days(oldestSince);
        return switch (kind) {
            case "OUT_OF_STOCK" -> others + " out of stock in " + store;
            case "RUNNING_LOW" -> others + " running low in " + store;
            case "NEEDS_APPROVAL" -> (n == 1 ? latest + " needs your approval" : n + " purchase orders need your approval") + waiting;
            case "NEEDS_SECOND_APPROVAL" -> (n == 1 ? latest + " needs a second approval" : n + " purchase orders need a second approval") + waiting;
            default -> message;
        };
    }

    private static String days(OffsetDateTime since) {
        long days = Duration.between(since, OffsetDateTime.now()).toDays();
        return days == 0 ? "less than a day" : days + (days == 1 ? " day" : " days");
    }
}
