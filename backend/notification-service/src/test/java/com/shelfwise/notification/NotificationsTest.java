package com.shelfwise.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.web.reactive.server.WebTestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The notification handler and the bell API (M21): grouped counts per store (D7), unread again only when a count
 * goes up, repeated and late events, each manager's own read state, finance groups, PO decisions, and token checks.
 */
@SpringBootTest
@Import(TestDatabaseConfig.class)
class NotificationsTest {

    private static final String ARJUN = TestTokens.token("U03", "STORE_MANAGER", "S01");
    private static final String IMRAN = TestTokens.token("U08", "STORE_MANAGER", "S01");   // S01's second manager (D26)
    private static final String SNEHA = TestTokens.token("U04", "STORE_MANAGER", "S02");
    private static final String MEERA = TestTokens.token("U10", "FINANCE_APPROVER", null);

    @Autowired
    private ApplicationContext context;
    @Autowired
    private NotificationHandler handler;
    @Autowired
    private DatabaseClient db;
    @Autowired
    private ObjectMapper json;

    private WebTestClient client;
    private OffsetDateTime clock;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToApplicationContext(context).build();
        db.sql("TRUNCATE notifications.reads, notifications.notifications, notifications.processed_events").then().block(Duration.ofSeconds(10));
        clock = OffsetDateTime.now().minusHours(1);
    }

    // --- store managers: grouped stock notifications ---------------------------------

    @Test
    void aStoresManagersSeeOneGroupedNotificationPerType_D7() {
        handle(stock("S01", 3, "Smart Buy Potato Chips 150g", 5, "Home Basics Iodised Salt 1kg"));

        Map<?, ?> feed = feed(ARJUN);
        assertThat(feed.get("unreadCount")).isEqualTo(2);
        assertThat(texts(feed)).containsExactlyInAnyOrder(
                "Smart Buy Potato Chips 150g and 2 other items are out of stock in S01",
                "Home Basics Iodised Salt 1kg and 4 other items are running low in S01");
        assertThat(items(feed(SNEHA))).isEmpty();                       // another store sees nothing
        assertThat(items(feed(MEERA))).isEmpty();                       // finance doesn't get stock groups
    }

    @Test
    void aGroupIsUnreadAgainOnlyWhenItsCountGoesUp_D7() {
        handle(stock("S01", 3, "Chips", 0, null));
        read(ARJUN, "/read-all");
        assertThat(feed(ARJUN).get("unreadCount")).isEqualTo(0);

        handle(stock("S01", 2, "Chips", 0, null));                      // restocked one: goes down, stays read
        assertThat(feed(ARJUN).get("unreadCount")).isEqualTo(0);
        assertThat(texts(feed(ARJUN))).containsExactly("Chips and 1 other item are out of stock in S01");

        handle(stock("S01", 4, "Salt", 0, null));                       // goes up: unread again, new latest item
        assertThat(feed(ARJUN).get("unreadCount")).isEqualTo(1);
        assertThat(texts(feed(ARJUN))).containsExactly("Salt and 3 other items are out of stock in S01");

        handle(stock("S01", 0, null, 0, null));                         // all restocked: the notification clears
        assertThat(items(feed(ARJUN))).isEmpty();
    }

    /** Each manager of a store has their own read state on the shared group (DL-12). */
    @Test
    void twoManagersOfOneStoreEachHaveTheirOwnUnreadState() {
        handle(stock("S01", 3, "Chips", 0, null));
        long id = ((Number) items(feed(ARJUN)).get(0).get("id")).longValue();

        read(ARJUN, "/" + id + "/read");

        assertThat(feed(ARJUN).get("unreadCount")).isEqualTo(0);
        assertThat(feed(IMRAN).get("unreadCount")).isEqualTo(1);
    }

    /** At-least-once delivery: the same event twice changes nothing; an older state arriving late is ignored. */
    @Test
    void repeatedOrLateEventsChangeNothing() {
        JsonNode newer = stock("S01", 5, "Chips", 0, null);
        JsonNode older = stockAt("S01", 9, "Old", clock.minusMinutes(30));

        assertThat(handler.handle(newer).block(Duration.ofSeconds(10))).isTrue();
        assertThat(handler.handle(newer).block(Duration.ofSeconds(10))).isFalse();
        handle(older);

        assertThat(texts(feed(ARJUN))).containsExactly("Chips and 4 other items are out of stock in S01");
    }

    // --- finance ---------------------------------------------------------------------

    @Test
    void financeSeesWaitingPosWithTheOldestAge_D7() {
        handle(event("ApprovalQueueChanged", "FINANCE", Map.of(
                "needsApproval", group(6, "PO-0011", OffsetDateTime.now().minusDays(3).minusHours(1)),
                "needsSecondApproval", group(1, "PO-0195", OffsetDateTime.now().minusHours(2)),
                "asOf", next().toString())));

        assertThat(texts(feed(MEERA))).containsExactlyInAnyOrder(
                "6 purchase orders need your approval · oldest waiting 3 days",
                "PO-0195 needs a second approval · oldest waiting less than a day");
        assertThat(items(feed(ARJUN))).isEmpty();
    }

    // --- PO decisions --------------------------------------------------------------------

    @Test
    void theStoreHearsOnlyFinalPoDecisions() {
        handle(decision("PO-10003", "APPROVED", "SUBMITTED", 1, 2, false, ""));     // 1 of 2: not final
        assertThat(items(feed(ARJUN))).isEmpty();

        handle(decision("PO-10003", "APPROVED", "APPROVED", 2, 2, false, ""));
        handle(decision("PO-10004", "REJECTED", "REJECTED", 0, 1, false, "Supplier price too high"));
        handle(decision("PO-0116", "APPROVED", "APPROVED", 1, 1, true, ""));

        assertThat(texts(feed(ARJUN))).containsExactly(                              // newest first
                "PO-0116 was re-approved; goods can be received",
                "PO-10004 was rejected: Supplier price too high",
                "PO-10003 was approved");
        assertThat(items(feed(SNEHA))).isEmpty();
    }

    // --- access ----------------------------------------------------------------------

    @Test
    void theBellNeedsAValidTokenFromTheCoreService() {
        client.get().uri("/api/notifications").exchange().expectStatus().isUnauthorized();
        String forged = TestTokens.sign("another-key-that-is-at-least-32-bytes-long", "U03", "STORE_MANAGER", "S01");
        client.get().uri("/api/notifications").headers(h -> h.setBearerAuth(forged)).exchange().expectStatus().isUnauthorized();
    }

    @Test
    void nobodyCanMarkAnotherStoresNotification() {
        handle(stock("S01", 3, "Chips", 0, null));
        long id = ((Number) items(feed(ARJUN)).get(0).get("id")).longValue();

        client.post().uri("/api/notifications/" + id + "/read").headers(h -> h.setBearerAuth(SNEHA))
                .exchange().expectStatus().isNotFound();
        assertThat(feed(ARJUN).get("unreadCount")).isEqualTo(1);
    }

    // --- helpers ---------------------------------------------------------------------

    private OffsetDateTime next() {
        clock = clock.plusSeconds(1);
        return clock;
    }

    private JsonNode stock(String store, int out, String outLatest, int low, String lowLatest) {
        return event("StockHealthChanged", store, Map.of(
                "storeId", store,
                "outOfStock", group(out, outLatest, null),
                "runningLow", group(low, lowLatest, null),
                "asOf", next().toString()));
    }

    private JsonNode stockAt(String store, int out, String latest, OffsetDateTime asOf) {
        return event("StockHealthChanged", store, Map.of(
                "storeId", store, "outOfStock", group(out, latest, null), "runningLow", group(0, null, null), "asOf", asOf.toString()));
    }

    private JsonNode decision(String po, String decision, String status, int valid, int needed, boolean reapproval, String reason) {
        return event("PurchaseOrderDecided", po, Map.of(
                "poId", po, "storeId", "S01", "decision", decision, "status", status, "validApprovals", valid,
                "approvalsNeeded", needed, "reapproval", reapproval, "reason", reason));
    }

    private static Map<String, Object> group(int count, String latest, OffsetDateTime oldest) {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("count", count);
        g.put("latest", latest);
        g.put("oldestSince", oldest == null ? null : oldest.toString());
        return g;
    }

    private JsonNode event(String type, String aggregateId, Map<String, Object> payload) {
        return json.valueToTree(Map.of("id", UUID.randomUUID().toString(), "type", type, "aggregateId", aggregateId,
                "occurredAt", next().toString(), "payload", payload));
    }

    private void handle(JsonNode event) {
        handler.handle(event).block(Duration.ofSeconds(10));
    }

    private Map<?, ?> feed(String token) {
        return client.get().uri("/api/notifications").headers(h -> h.setBearerAuth(token))
                .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
    }

    private void read(String token, String path) {
        client.post().uri("/api/notifications" + path).headers(h -> h.setBearerAuth(token)).exchange().expectStatus().isNoContent();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<?, ?> feed) {
        return (List<Map<String, Object>>) feed.get("items");
    }

    private static List<Object> texts(Map<?, ?> feed) {
        return items(feed).stream().map(i -> i.get("text")).toList();
    }
}
