package com.shelfwise.core.orders;

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
import com.shelfwise.core.auth.UserAccount;
import com.shelfwise.core.orders.OrderDtos.CreateOrderRequest;
import com.shelfwise.core.orders.OrderDtos.LineRequest;

import reactor.core.publisher.Flux;

/** Orders API end to end (M11): create with stock following the order, status flow, cancel, and overlapping orders (M12). */
@SpringBootTest
@Import(TestDatabaseConfig.class)
class OrdersApiTest {

    private static final String PASSWORD = "Test-Only-Pass-1";
    private static final String PHONE = "+91 9873819191";

    @Autowired
    private ApplicationContext context;
    @Autowired
    private DatabaseClient db;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private OrderService orderService;

    private WebTestClient client;
    private String manager;
    private String otherManager;
    private String admin;
    private String finance;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToApplicationContext(context).build();
        run("TRUNCATE order_lines, customer_orders, stock_movements, inventory, products, suppliers, refresh_tokens, users, stores CASCADE");
        run("INSERT INTO stores (id, name, city, state, opened_on) VALUES ('S01','Whitefield','Bengaluru','Karnataka','2019-03-15'), ('S02','Madhapur','Hyderabad','Telangana','2020-04-15')");
        db.sql("""
                INSERT INTO users (id, name, email, role, store_id, active, password_hash) VALUES
                  ('U01','Rohit Menon','rohit@test.example','ADMIN',NULL,true,:h),
                  ('U03','Arjun Rao','arjun@test.example','STORE_MANAGER','S01',true,:h),
                  ('U04','Sneha Reddy','sneha@test.example','STORE_MANAGER','S02',true,:h),
                  ('U10','Meera Joshi','meera@test.example','FINANCE_APPROVER',NULL,true,:h)
                """).bind("h", passwordEncoder.encode(PASSWORD)).then().block(Duration.ofSeconds(10));
        run("INSERT INTO suppliers (id, name) VALUES ('SUP23','Supplier SUP23')");
        run("""
            INSERT INTO products (sku, name, category, price_inr, cost_price_inr, gst_rate, supplier_id, status, reorder_level, catalogued_on, source) VALUES
              ('SW-SNK-00091','Smart Buy Potato Chips 150g','Snacks',25,20,12,'SUP23','ACTIVE',25,'2025-12-02','IMPORT'),
              ('SW-GRO-00055','Home Basics Iodised Salt 1kg','Grocery',31,25,0,'SUP23','ACTIVE',10,'2025-01-01','IMPORT'),
              ('SW-BEV-00005','Green Leaf Instant Coffee 50g','Beverages',NULL,169,12,'SUP23','ACTIVE',10,'2025-01-01','IMPORT'),
              ('SW-SNK-00064','Fresh Basket Chocolate Bar 100g','Snacks',52,42,12,'SUP23','ARCHIVED',25,'2025-04-20','IMPORT')
            """);
        run("""
            INSERT INTO inventory (store_id, sku, quantity, reorder_level) VALUES
              ('S01','SW-SNK-00091',37,25), ('S01','SW-GRO-00055',3,10), ('S01','SW-BEV-00005',20,10), ('S01','SW-SNK-00064',81,25)
            """);
        manager = login("arjun@test.example");
        otherManager = login("sneha@test.example");
        admin = login("rohit@test.example");
        finance = login("meera@test.example");
    }

    // --- create --------------------------------------------------------------

    @Test
    void creatingAnOrderTakesStockAndSnapshotsPrices_D29_D15() {
        Map<?, ?> order = create(manager, List.of(line("SW-SNK-00091", 2), line("SW-GRO-00055", 1)), null, 201);

        assertThat(order.get("status")).isEqualTo("PENDING");
        assertThat(((Number) order.get("totalAmount")).intValue()).isEqualTo(2 * 25 + 31);
        assertThat(stock("SW-SNK-00091")).isEqualTo(35);
        assertThat(stock("SW-GRO-00055")).isEqualTo(2);
        assertThat(number("SELECT count(*) FROM stock_movements WHERE reason = 'ORDER_CREATED' AND reference_id = '" + order.get("id") + "'")).isEqualTo(2);
        // a later price change never rewrites this order (D15)
        run("UPDATE products SET price_inr = 99 WHERE sku = 'SW-SNK-00091'");
        get(manager, "/api/orders/" + order.get("id")).jsonPath("$.lines[0].unitPrice").isEqualTo(25);
    }

    @Test
    void ifAnyLineIsShortNothingIsSavedAndNoStockMoves() {
        create(manager, List.of(line("SW-SNK-00091", 2), line("SW-GRO-00055", 5)), null, 409);   // only 3 salt

        assertThat(stock("SW-SNK-00091")).isEqualTo(37);
        assertThat(number("SELECT count(*) FROM customer_orders")).isZero();
        assertThat(number("SELECT count(*) FROM stock_movements")).isZero();
    }

    @Test
    void onlySellableProductsCanBeOrdered_D30() {
        create(manager, List.of(line("SW-SNK-00064", 1)), null, 409);   // archived (D3)
        create(manager, List.of(line("SW-BEV-00005", 1)), null, 409);   // no price (D2)
        create(manager, List.of(line("SW-ZZZ-00001", 1)), null, 400);   // unknown (D12)
        create(manager, List.of(line("SW-SNK-00091", 0)), null, 400);   // quantity of at least 1 (D10)
    }

    @Test
    void customerDetailsAreValidated_D16() {
        client.post().uri("/api/orders").headers(h -> h.setBearerAuth(manager))
                .bodyValue(Map.of("customerName", "Zoya Joshi", "customerPhone", "12345", "lines", List.of(line("SW-SNK-00091", 1))))
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    void theSameProductTwiceBecomesOneLine_D14() {
        Map<?, ?> order = create(manager, List.of(line("SW-SNK-00091", 2), line("SW-SNK-00091", 3)), null, 201);
        get(manager, "/api/orders/" + order.get("id")).jsonPath("$.lines.length()").isEqualTo(1).jsonPath("$.lines[0].qty").isEqualTo(5);
    }

    @Test
    void onlyStoreManagersCreateOrdersInTheirOwnStore() {
        create(admin, List.of(line("SW-SNK-00091", 1)), null, 403);
        create(finance, List.of(line("SW-SNK-00091", 1)), null, 403);
        create(otherManager, List.of(line("SW-SNK-00091", 1)), null, 409);   // S02 has no stock row: their order is in S02
        assertThat(stock("SW-SNK-00091")).isEqualTo(37);                      // S01 stock untouched
    }

    /** A double tap or a network retry with the same key creates ONE order and takes stock once (D8). */
    @Test
    void retryingWithTheSameIdempotencyKeyCreatesOneOrder_D8() {
        Map<?, ?> first = create(manager, List.of(line("SW-SNK-00091", 2)), "key-123", 201);
        Map<?, ?> second = create(manager, List.of(line("SW-SNK-00091", 2)), "key-123", 201);

        assertThat(second.get("id")).isEqualTo(first.get("id"));
        assertThat(number("SELECT count(*) FROM customer_orders")).isEqualTo(1);
        assertThat(stock("SW-SNK-00091")).isEqualTo(35);
    }

    // --- read ----------------------------------------------------------------

    @Test
    void phonesAreMaskedExceptForTheStoresOwnManager_D16() {
        Map<?, ?> order = create(manager, List.of(line("SW-SNK-00091", 1)), null, 201);
        get(manager, "/api/orders").jsonPath("$.items[0].customerPhone").isEqualTo("+91 98XXXXX191");
        get(manager, "/api/orders/" + order.get("id")).jsonPath("$.customerPhone").isEqualTo(PHONE);
        get(admin, "/api/orders/" + order.get("id")).jsonPath("$.customerPhone").isEqualTo("+91 98XXXXX191");
        client.get().uri("/api/orders/" + order.get("id")).headers(h -> h.setBearerAuth(otherManager)).exchange().expectStatus().isForbidden();
    }

    @Test
    void oldOpenOrdersAreFlaggedStuck_D32() {
        Map<?, ?> order = create(manager, List.of(line("SW-SNK-00091", 1)), null, 201);
        run("UPDATE customer_orders SET created_at = now() - interval '10 days' WHERE id = '" + order.get("id") + "'");
        get(manager, "/api/orders/" + order.get("id")).jsonPath("$.stuck").isEqualTo(true);
    }

    // --- status --------------------------------------------------------------

    @Test
    void ordersMoveThroughTheirLifecycleOnly() {
        Map<?, ?> order = create(manager, List.of(line("SW-SNK-00091", 1)), null, 201);
        String id = (String) order.get("id");
        status(manager, id, "DELIVERED", 0, 409);   // can't skip steps
        status(manager, id, "PACKED", 0, 200);
        status(manager, id, "SHIPPED", 1, 200);
        status(manager, id, "CANCELLED", 2, 409);   // shipped orders can't be cancelled (D29)
        status(manager, id, "DELIVERED", 2, 200);
    }

    @Test
    void cancellingGivesTheStockBackOnce_D29() {
        Map<?, ?> order = create(manager, List.of(line("SW-SNK-00091", 4)), null, 201);
        String id = (String) order.get("id");
        assertThat(stock("SW-SNK-00091")).isEqualTo(33);

        status(manager, id, "CANCELLED", 0, 200);
        status(manager, id, "CANCELLED", 1, 409);   // cancelling twice doesn't return stock twice

        assertThat(stock("SW-SNK-00091")).isEqualTo(37);
        assertThat(number("SELECT count(*) FROM stock_movements WHERE reason = 'ORDER_CANCELLED'")).isEqualTo(1);
    }

    @Test
    void aStaleVersionIsRefused() {
        Map<?, ?> order = create(manager, List.of(line("SW-SNK-00091", 1)), null, 201);
        status(manager, (String) order.get("id"), "PACKED", 0, 200);
        status(admin, (String) order.get("id"), "SHIPPED", 0, 409);   // admin acted on the page loaded before "packed"
    }

    // --- M12 -------------------------------------------------------------------

    /** Five customers order the last 3 bags of salt at the same moment: exactly 3 orders succeed, stock ends at 0. */
    @Test
    void overlappingOrdersNeverOversell_M12() {
        UserAccount arjun = new UserAccount("U03", "Arjun Rao", "arjun@test.example", UserAccount.Role.STORE_MANAGER, "S01", true, null);
        List<String> outcomes = Flux.range(0, 5)
                .flatMap(i -> orderService.create(arjun,
                                new CreateOrderRequest("Customer " + i, PHONE, List.of(new LineRequest("SW-GRO-00055", 1))), null)
                        .map(o -> "ok").onErrorReturn("refused"), 5)
                .collectList().block(Duration.ofSeconds(30));

        assertThat(outcomes).filteredOn("ok"::equals).hasSize(3);
        assertThat(stock("SW-GRO-00055")).isZero();
        assertThat(number("SELECT count(*) FROM customer_orders")).isEqualTo(3);   // the refused ones left nothing behind
    }

    // --- helpers ---------------------------------------------------------------

    private static Map<String, Object> line(String sku, int qty) {
        return Map.of("sku", sku, "qty", qty);
    }

    private Map<?, ?> create(String token, List<Map<String, Object>> lines, String key, int status) {
        return client.post().uri("/api/orders").headers(h -> {
                    h.setBearerAuth(token);
                    if (key != null) {
                        h.add("Idempotency-Key", key);
                    }
                })
                .bodyValue(Map.of("customerName", "Zoya Joshi", "customerPhone", PHONE, "lines", lines))
                .exchange().expectStatus().isEqualTo(status)
                .expectBody(Map.class).returnResult().getResponseBody();
    }

    private void status(String token, String id, String to, long version, int status) {
        client.post().uri("/api/orders/" + id + "/status").headers(h -> h.setBearerAuth(token))
                .bodyValue(Map.of("to", to, "version", version)).exchange().expectStatus().isEqualTo(status);
    }

    private WebTestClient.BodyContentSpec get(String token, String uri) {
        return client.get().uri(uri).headers(h -> h.setBearerAuth(token)).exchange().expectStatus().isOk().expectBody();
    }

    private String login(String email) {
        return client.post().uri("/api/auth/login").bodyValue(Map.of("email", email, "password", PASSWORD))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody().get("accessToken").toString();
    }

    private long stock(String sku) {
        return number("SELECT quantity FROM inventory WHERE store_id = 'S01' AND sku = '" + sku + "'");
    }

    private long number(String sql) {
        return db.sql(sql).map((row, meta) -> ((Number) row.get(0)).longValue()).one().block(Duration.ofSeconds(10));
    }

    private void run(String sql) {
        db.sql(sql).then().block(Duration.ofSeconds(10));
    }
}
