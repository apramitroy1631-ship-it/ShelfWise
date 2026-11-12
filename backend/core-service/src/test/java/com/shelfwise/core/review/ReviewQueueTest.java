package com.shelfwise.core.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

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
 * The admin review queue (D10): flagged imported orders stay out of revenue until an admin corrects
 * (right quantity or price, with a reason) or rejects them. One decision per order, audited, stock untouched.
 */
@SpringBootTest
@Import(TestDatabaseConfig.class)
class ReviewQueueTest {

    private static final String PASSWORD = "Test-Only-Pass-1";

    @Autowired
    private DatabaseClient db;
    @Autowired
    private ApplicationContext context;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private WebTestClient client;
    private String admin;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToApplicationContext(context).build();
        run("""
            TRUNCATE audit_log, outbox_events, order_lines, customer_orders, stock_movements, inventory, products, suppliers,
                     refresh_tokens, users, stores CASCADE
            """);
        run("INSERT INTO stores (id, name, city, state, opened_on) VALUES ('S01','Whitefield','Bengaluru','Karnataka','2019-03-15'), ('S02','Madhapur','Hyderabad','Telangana','2020-04-15')");
        db.sql("""
                INSERT INTO users (id, name, email, role, store_id, active, password_hash) VALUES
                  ('U01','Rohit Menon','rohit@test.example','ADMIN',NULL,true,:h),
                  ('U02','Kavya Iyer','kavya@test.example','ADMIN',NULL,true,:h),
                  ('U03','Arjun Rao','arjun@test.example','STORE_MANAGER','S01',true,:h),
                  ('U09','Meera Joshi','meera@test.example','FINANCE_APPROVER',NULL,true,:h)
                """).bind("h", passwordEncoder.encode(PASSWORD)).then().block(Duration.ofSeconds(10));
        run("INSERT INTO suppliers (id, name) VALUES ('SUP23','Supplier SUP23')");
        run("""
            INSERT INTO products (sku, name, category, price_inr, cost_price_inr, gst_rate, supplier_id, status, reorder_level, catalogued_on, source) VALUES
              ('SW-PER-00079','Glow Soap 100g','Personal Care',113,90,18,'SUP23','ACTIVE',10,'2025-01-01','IMPORT'),
              ('SW-GRO-00072','Home Basics Rice 1kg','Grocery',112,90,0,'SUP23','ACTIVE',10,'2025-01-01','IMPORT')
            """);
        run("INSERT INTO inventory (store_id, sku, quantity, reorder_level) VALUES ('S01','SW-PER-00079',37,10), ('S01','SW-GRO-00072',12,10)");

        // Like ORD-011525: only the negative line. Like ORD-007191: a negative line among normal ones.
        order("ORD-011525", "S01", "-226.00", "NEEDS_REVIEW", "2026-03-01T10:00:00+05:30");
        line("ORD-011525", "SW-PER-00079", -2, "113", "RECORDED");
        order("ORD-007191", "S02", "-2.00", "NEEDS_REVIEW", "2026-04-27T11:58:06+05:30");
        line("ORD-007191", "SW-PER-00079", -2, "113", "RECORDED");
        line("ORD-007191", "SW-GRO-00072", 2, "112", "RECORDED");
        // D11's last step: a missing price nothing could prove (none in today's data; the rule must still work).
        order("ORD-020001", "S01", "0.00", "NEEDS_REVIEW", "2026-05-01T09:00:00+05:30");
        line("ORD-020001", "SW-GRO-00072", 3, "0", "ADMIN");
        // A normal delivered order: the only revenue before any review.
        order("ORD-000500", "S01", "500.00", null, "2026-02-01T09:00:00+05:30");
        line("ORD-000500", "SW-GRO-00072", 5, "100", "RECORDED");

        admin = login("rohit@test.example");
    }

    // --- the list --------------------------------------------------------------------

    @Test
    void theQueueListsWaitingOrdersOldestFirstWithWhatIsWrong() {
        Map<?, ?> body = get(admin, "/api/admin/reviews");

        assertThat(number(body.get("waiting"))).isEqualTo(3);
        List<Map<?, ?>> items = items(body);
        assertThat(items).extracting(i -> String.valueOf(i.get("orderId"))).containsExactly("ORD-011525", "ORD-007191", "ORD-020001");
        assertThat(((List<?>) items.get(1).get("problems")).stream().map(String::valueOf).toList()).containsExactly("'SW-PER-00079' has quantity -2 (D10)");
        List<Map<?, ?>> lines = lines(items.get(1));
        assertThat(lines.get(0).get("problem")).isEqualTo("NEGATIVE_QTY");
        assertThat(lines.get(1).get("problem")).isNull();
        assertThat(lines(items.get(2)).get(0).get("problem")).isEqualTo("NO_PRICE");

        assertThat(items(get(admin, "/api/admin/reviews?storeId=S02"))).extracting(i -> String.valueOf(i.get("orderId"))).containsExactly("ORD-007191");
        assertThat(items(get(admin, "/api/admin/reviews?state=DECIDED"))).isEmpty();
    }

    // --- correct ----------------------------------------------------------------------

    @Test
    void correctingSetsTheQuantityRecalculatesTheTotalAndTheOrderJoinsRevenue() {
        assertThat(number(get(admin, "/api/dashboard").get("revenue"))).isEqualTo(500);

        Map<?, ?> item = post(admin, "/api/admin/reviews/ORD-007191/correct", Map.of("version", 0,
                "lines", List.of(Map.of("sku", "SW-PER-00079", "qty", 2)), "reason", "Typed as -2 at the till; receipt shows 2"))
                .expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();

        assertThat(item.get("reviewStatus")).isEqualTo("CORRECTED");
        assertThat(number(item.get("totalAmount"))).isEqualTo(450);                  // 2 x 113 + 2 x 112
        assertThat(number(item.get("originalTotal"))).isEqualTo(-2);                 // the before stays visible
        assertThat(item.get("reviewedByName")).isEqualTo("Rohit Menon");
        assertThat(item.get("reviewNote")).isEqualTo("Typed as -2 at the till; receipt shows 2");
        Map<?, ?> fixed = lines(item).get(0);
        assertThat(number(fixed.get("qty"))).isEqualTo(2);
        assertThat(number(fixed.get("originalQty"))).isEqualTo(-2);
        assertThat(fixed.get("problem")).isEqualTo("NEGATIVE_QTY");                  // still shows what was wrong
        assertThat(lines(item).get(1).get("originalQty")).isNull();                  // untouched line

        assertThat(number(get(admin, "/api/dashboard").get("revenue"))).isEqualTo(950);   // 500 + 450
        assertThat(count("SELECT quantity FROM inventory WHERE store_id = 'S01' AND sku = 'SW-PER-00079'")).isEqualTo(37);   // stock untouched
        assertThat(count("SELECT count(*) FROM stock_movements")).isZero();
        assertThat(count("""
                SELECT count(*) FROM audit_log WHERE action = 'ORDER_REVIEW_CORRECTED' AND entity_id = 'ORD-007191'
                  AND actor_user_id = 'U01' AND reason IS NOT NULL
                  AND (before_value ->> 'totalAmount')::numeric = -2 AND (after_value ->> 'totalAmount')::numeric = 450
                """)).isEqualTo(1);

        Map<?, ?> lists = get(admin, "/api/admin/reviews?state=DECIDED");
        assertThat(number(lists.get("waiting"))).isEqualTo(2);
        assertThat(items(lists)).extracting(i -> String.valueOf(i.get("orderId"))).containsExactly("ORD-007191");
    }

    @Test
    void aMissingPriceIsSetByTheAdmin_D11() {
        post(admin, "/api/admin/reviews/ORD-020001/correct", Map.of("version", 0,
                "lines", List.of(Map.of("sku", "SW-GRO-00072")), "reason", "Price from the paper bill"))
                .expectStatus().isBadRequest();

        Map<?, ?> item = post(admin, "/api/admin/reviews/ORD-020001/correct", Map.of("version", 0,
                "lines", List.of(Map.of("sku", "SW-GRO-00072", "unitPrice", 112)), "reason", "Price from the paper bill"))
                .expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
        assertThat(number(item.get("totalAmount"))).isEqualTo(336);
        assertThat(number(lines(item).get(0).get("originalUnitPrice"))).isZero();
    }

    // --- reject -----------------------------------------------------------------------

    @Test
    void rejectingKeepsTheOrderOutOfRevenueWithItsReason() {
        Map<?, ?> item = post(admin, "/api/admin/reviews/ORD-011525/reject", Map.of("version", 0, "reason", "Can't tell what was sold"))
                .expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();

        assertThat(item.get("reviewStatus")).isEqualTo("REJECTED");
        assertThat(number(item.get("totalAmount"))).isEqualTo(-226);              // kept exactly as received
        assertThat(number(get(admin, "/api/dashboard").get("revenue"))).isEqualTo(500);
        assertThat(count("SELECT count(*) FROM audit_log WHERE action = 'ORDER_REVIEW_REJECTED' AND entity_id = 'ORD-011525'")).isEqualTo(1);
    }

    // --- one decision only --------------------------------------------------------------

    @Test
    void anOrderCanBeDecidedOnlyOnce() {
        post(admin, "/api/admin/reviews/ORD-011525/reject", Map.of("version", 0, "reason", "Can't tell what was sold"))
                .expectStatus().isOk();

        post(admin, "/api/admin/reviews/ORD-011525/correct", Map.of("version", 1,
                "lines", List.of(Map.of("sku", "SW-PER-00079", "qty", 2)), "reason", "Second thoughts"))
                .expectStatus().isEqualTo(409);
        post(admin, "/api/admin/reviews/ORD-011525/reject", Map.of("version", 0, "reason", "Again"))
                .expectStatus().isEqualTo(409);
    }

    @Test
    void twoAdminsAtOnce_exactlyOneDecisionWins() {
        String kavya = login("kavya@test.example");
        CompletableFuture<Integer> a = CompletableFuture.supplyAsync(() -> post(admin, "/api/admin/reviews/ORD-011525/correct",
                Map.of("version", 0, "lines", List.of(Map.of("sku", "SW-PER-00079", "qty", 2)), "reason", "Receipt shows 2"))
                .returnResult(Map.class).getStatus().value());
        CompletableFuture<Integer> b = CompletableFuture.supplyAsync(() -> post(kavya, "/api/admin/reviews/ORD-011525/reject",
                Map.of("version", 0, "reason", "Can't tell")).returnResult(Map.class).getStatus().value());

        assertThat(List.of(a.join(), b.join())).containsExactlyInAnyOrder(200, 409);
        assertThat(count("SELECT count(*) FROM audit_log WHERE entity_id = 'ORD-011525'")).isEqualTo(1);
    }

    // --- rules ------------------------------------------------------------------------

    @Test
    void badCorrectionsAreRefused() {
        String path = "/api/admin/reviews/ORD-007191/correct";
        // quantity 0 or below
        post(admin, path, Map.of("version", 0, "lines", List.of(Map.of("sku", "SW-PER-00079", "qty", 0)), "reason", "x"))
                .expectStatus().isBadRequest();
        // no reason
        post(admin, path, Map.of("version", 0, "lines", List.of(Map.of("sku", "SW-PER-00079", "qty", 2)), "reason", " "))
                .expectStatus().isBadRequest();
        // a line that isn't flagged
        post(admin, path, Map.of("version", 0, "lines", List.of(Map.of("sku", "SW-PER-00079", "qty", 2), Map.of("sku", "SW-GRO-00072", "qty", 9)),
                "reason", "x")).expectStatus().isBadRequest();
        // the recorded price of a D10 line (D15)
        post(admin, path, Map.of("version", 0, "lines", List.of(Map.of("sku", "SW-PER-00079", "qty", 2, "unitPrice", 99)), "reason", "x"))
                .expectStatus().isBadRequest();
        // a stale version
        post(admin, path, Map.of("version", 7, "lines", List.of(Map.of("sku", "SW-PER-00079", "qty", 2)), "reason", "x"))
                .expectStatus().isEqualTo(409);
        // an order that isn't in the queue
        post(admin, "/api/admin/reviews/ORD-000500/correct", Map.of("version", 0, "lines", List.of(Map.of("sku", "SW-GRO-00072", "qty", 2)),
                "reason", "x")).expectStatus().isNotFound();

        assertThat(count("SELECT count(*) FROM customer_orders WHERE review_status = 'NEEDS_REVIEW'")).isEqualTo(3);   // nothing changed
        assertThat(count("SELECT count(*) FROM audit_log")).isZero();
    }

    @Test
    void onlyAdminsSeeOrDecide() {
        for (String other : List.of(login("arjun@test.example"), login("meera@test.example"))) {
            client.get().uri("/api/admin/reviews").headers(h -> h.setBearerAuth(other)).exchange().expectStatus().isForbidden();
            post(other, "/api/admin/reviews/ORD-011525/reject", Map.of("version", 0, "reason", "x")).expectStatus().isForbidden();
        }
        client.get().uri("/api/admin/reviews").exchange().expectStatus().isUnauthorized();
    }

    // --- helpers ----------------------------------------------------------------------

    private void order(String id, String store, String total, String review, String createdAt) {
        DatabaseClient.GenericExecuteSpec spec = db.sql("""
                INSERT INTO customer_orders (id, store_id, placed_by, status, customer_name, customer_phone, total_amount, created_at, source, review_status)
                VALUES (:id, :store, 'U03', 'DELIVERED', 'Tara Bose', '+91 9810710625', :total::numeric, :at::timestamptz, 'IMPORT', :review)
                """).bind("id", id).bind("store", store).bind("total", total).bind("at", createdAt);
        spec = review == null ? spec.bindNull("review", String.class) : spec.bind("review", review);
        spec.then().block(Duration.ofSeconds(10));
    }

    private void line(String orderId, String sku, int qty, String price, String source) {
        db.sql("""
                INSERT INTO order_lines (order_id, sku_text, product_sku, qty, unit_price, price_source, imported)
                VALUES (:id, :sku, :sku, :qty, :price::numeric, :source, true)
                """).bind("id", orderId).bind("sku", sku).bind("qty", qty).bind("price", price).bind("source", source)
                .then().block(Duration.ofSeconds(10));
    }

    private Map<?, ?> get(String token, String uri) {
        return client.get().uri(uri).headers(h -> h.setBearerAuth(token))
                .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
    }

    private WebTestClient.ResponseSpec post(String token, String uri, Object body) {
        return client.post().uri(uri).headers(h -> h.setBearerAuth(token)).bodyValue(body).exchange();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<?, ?>> items(Map<?, ?> body) {
        return (List<Map<?, ?>>) body.get("items");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<?, ?>> lines(Map<?, ?> item) {
        return (List<Map<?, ?>>) item.get("lines");
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

    private static long number(Object value) {
        return Math.round(((Number) value).doubleValue());
    }
}
