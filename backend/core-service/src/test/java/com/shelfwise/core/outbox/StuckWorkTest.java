package com.shelfwise.core.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.shelfwise.core.TestDatabaseConfig;

/**
 * Stuck work and head office in the bell (D7): the "stuck only" lists a notification opens (D32 orders, D22 POs),
 * the current counts the relay sends, and the markers that tell it when to send them.
 */
@SpringBootTest
@Import(TestDatabaseConfig.class)
class StuckWorkTest {

    private static final String PASSWORD = "Test-Only-Pass-1";

    @Autowired
    private DatabaseClient db;
    @Autowired
    private ApplicationContext context;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private StateSnapshots snapshots;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToApplicationContext(context).build();
        run("""
            TRUNCATE outbox_events, audit_log, po_receipts, po_approvals, po_lines, purchase_orders, order_lines, customer_orders,
                     stock_movements, inventory, products, suppliers, refresh_tokens, users, stores CASCADE
            """);
        run("INSERT INTO stores (id, name, city, state, opened_on) VALUES ('S01','Whitefield','Bengaluru','Karnataka','2019-03-15'), ('S02','Madhapur','Hyderabad','Telangana','2020-04-15')");
        db.sql("""
                INSERT INTO users (id, name, email, role, store_id, active, password_hash) VALUES
                  ('U01','Rohit Menon','rohit@test.example','ADMIN',NULL,true,:h),
                  ('U03','Arjun Rao','arjun@test.example','STORE_MANAGER','S01',true,:h),
                  ('U10','Meera Joshi','meera@test.example','FINANCE_APPROVER',NULL,true,:h)
                """).bind("h", passwordEncoder.encode(PASSWORD)).then().block(Duration.ofSeconds(10));
        // TRUNCATE users CASCADE also empties app_settings: put the two limits back at their defaults
        run("""
            INSERT INTO app_settings (key, value, description) VALUES ('orders.stuckAfterDays', '7', 'test'), ('purchaseOrders.stuckAfterDays', '30', 'test')
            ON CONFLICT (key) DO UPDATE SET value = excluded.value
            """);
        run("INSERT INTO suppliers (id, name) VALUES ('SUP23','Supplier SUP23')");

        order("ORD-000010", "S01", "PENDING", 10);      // stuck
        order("ORD-000011", "S01", "PACKED", 2);        // open but young
        order("ORD-000012", "S01", "DELIVERED", 30);    // old but finished
        order("ORD-000013", "S02", "SHIPPED", 20);      // stuck, other store

        po("PO-0101", "S01", "APPROVED", 100);  approval("PO-0101", 40);    // waiting 40 days since approval: stuck
        po("PO-0102", "S01", "APPROVED", 100);  approval("PO-0102", 5);     // approved 5 days ago: not yet
        po("PO-0103", "S01", "RECEIVED", 200);  approval("PO-0103", 190);   // finished
        po("PO-0104", "S01", "PARTIALLY_RECEIVED", 60);                     // no approval on record: counts from creation, stuck
    }

    // --- the lists a stuck notification opens ----------------------------------------------

    @Test
    void stuckOrdersAreOpenOrdersPastTheLimit_D32() {
        assertThat(ids(get(login("arjun@test.example"), "/api/orders?stuck=true"))).containsExactly("ORD-000010");
        Map<?, ?> all = get(login("rohit@test.example"), "/api/orders?stuck=true");
        assertThat(ids(all)).containsExactlyInAnyOrder("ORD-000010", "ORD-000013");
        assertThat(((List<?>) all.get("items")).stream().map(i -> String.valueOf(((Map<?, ?>) i).get("stuck")))).containsOnly("true");
        assertThat(ids(get(login("rohit@test.example"), "/api/orders?stuck=true&storeId=S02"))).containsExactly("ORD-000013");
    }

    @Test
    void theLimitComesFromRemoteConfig() {
        run("UPDATE app_settings SET value = '1' WHERE key = 'orders.stuckAfterDays'");
        assertThat(ids(get(login("arjun@test.example"), "/api/orders?stuck=true"))).containsExactlyInAnyOrder("ORD-000010", "ORD-000011");
        run("UPDATE app_settings SET value = '45' WHERE key = 'purchaseOrders.stuckAfterDays'");
        assertThat(ids(get(login("arjun@test.example"), "/api/purchase-orders?stuck=true"))).containsExactly("PO-0104");
    }

    @Test
    void stuckPurchaseOrdersWaitForGoodsPastTheLimitSinceApproval_D22() {
        Map<?, ?> page = get(login("arjun@test.example"), "/api/purchase-orders?stuck=true");
        assertThat(ids(page)).containsExactlyInAnyOrder("PO-0101", "PO-0104");
        assertThat(((List<?>) page.get("items")).stream().map(i -> String.valueOf(((Map<?, ?>) i).get("stuck")))).containsOnly("true");
        assertThat(ids(get(login("arjun@test.example"), "/api/purchase-orders"))).hasSize(4);   // without the filter: everything
    }

    // --- what the relay sends --------------------------------------------------------------

    @Test
    void theStuckSnapshotNamesTheLongestWaitingOne() {
        Map<String, Object> s01 = snapshots.stuckWork("S01").block(Duration.ofSeconds(10));

        Map<?, ?> orders = (Map<?, ?>) s01.get("stuckOrders");
        assertThat(orders.get("count")).isEqualTo(1L);
        assertThat(orders.get("latest")).isEqualTo("ORD-000010");
        Map<?, ?> pos = (Map<?, ?>) s01.get("stuckPurchaseOrders");
        assertThat(pos.get("count")).isEqualTo(2L);
        assertThat(pos.get("latest")).isEqualTo("PO-0104");                 // 60 days beats 40
        assertThat(pos.get("oldestSince")).isNotNull();
    }

    @Test
    void headOfficeSeesOrdersWaitingForReview_D10() {
        run("UPDATE customer_orders SET review_status = 'NEEDS_REVIEW' WHERE id IN ('ORD-000012', 'ORD-000013')");

        Map<?, ?> review = (Map<?, ?>) snapshots.headOffice().block(Duration.ofSeconds(10)).get("needsReview");
        assertThat(review.get("count")).isEqualTo(2L);
        assertThat(review.get("latest")).isEqualTo("ORD-000012");          // waiting longest
    }

    // --- when it is sent ---------------------------------------------------------------------

    @Test
    void movingAnOrderOnTellsTheBellItsStoreChanged() {
        Map<?, ?> o = get(login("arjun@test.example"), "/api/orders/ORD-000010");
        client.post().uri("/api/orders/ORD-000010/status").headers(h -> h.setBearerAuth(login("arjun@test.example")))
                .bodyValue(Map.of("to", "PACKED", "version", o.get("version"))).exchange().expectStatus().isOk();

        assertThat(count("SELECT count(*) FROM outbox_events WHERE event_type = 'StuckWorkChanged' AND aggregate_id = 'S01'")).isEqualTo(1);
    }

    @Test
    void closingAStuckPoTellsTheBellItsStoreChanged() {
        long version = count("SELECT version FROM purchase_orders WHERE id = 'PO-0101'");
        client.post().uri("/api/purchase-orders/PO-0101/close").headers(h -> h.setBearerAuth(login("arjun@test.example")))
                .bodyValue(Map.of("reason", "Supplier stopped this line", "version", version)).exchange().expectStatus().isOk();

        assertThat(count("SELECT count(*) FROM outbox_events WHERE event_type = 'StuckWorkChanged' AND aggregate_id = 'S01'")).isEqualTo(1);
        assertThat(((Map<?, ?>) snapshots.stuckWork("S01").block(Duration.ofSeconds(10)).get("stuckPurchaseOrders")).get("count")).isEqualTo(1L);
    }

    // --- helpers -----------------------------------------------------------------------------

    private void order(String id, String store, String status, int daysAgo) {
        run("INSERT INTO customer_orders (id, store_id, placed_by, status, customer_name, customer_phone, total_amount, created_at, source) "
                + "VALUES ('" + id + "','" + store + "','U03','" + status + "','Tara Bose','+91 9810710625',100,now() - interval '" + daysAgo + " days','IMPORT')");
    }

    private void po(String id, String store, String status, int daysAgo) {
        run("INSERT INTO purchase_orders (id, store_id, supplier_id, created_by, status, total_amount, created_at, source) "
                + "VALUES ('" + id + "','" + store + "','SUP23','U03','" + status + "',1000,now() - interval '" + daysAgo + " days','IMPORT')");
    }

    private void approval(String po, int daysAgo) {
        run("INSERT INTO po_approvals (po_id, approver_id, decision, decided_at, counts, step) "
                + "VALUES ('" + po + "','U10','APPROVED',now() - interval '" + daysAgo + " days',true,1)");
    }

    private static List<String> ids(Map<?, ?> page) {
        return ((List<?>) page.get("items")).stream().map(i -> String.valueOf(((Map<?, ?>) i).get("id"))).toList();
    }

    private Map<?, ?> get(String token, String uri) {
        return client.get().uri(uri).headers(h -> h.setBearerAuth(token))
                .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
    }

    private String login(String email) {
        return client.post().uri("/api/auth/login").bodyValue(Map.of("email", email, "password", PASSWORD))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody().get("accessToken").toString();
    }

    private long count(String sql) {
        return db.sql(sql).map((row, meta) -> ((Number) row.get(0)).longValue()).one().block(Duration.ofSeconds(10));
    }

    private void run(String sql) {
        db.sql(sql).then().block(Duration.ofSeconds(30));
    }
}
