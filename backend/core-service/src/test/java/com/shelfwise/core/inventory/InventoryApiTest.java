package com.shelfwise.core.inventory;

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
import com.shelfwise.core.inventory.InventoryDtos.AdjustmentRequest;

import reactor.core.publisher.Flux;

/** Inventory API end to end (M10) and stock correctness under overlapping changes (M12). */
@SpringBootTest
@Import(TestDatabaseConfig.class)
class InventoryApiTest {

    private static final String PASSWORD = "Test-Only-Pass-1";

    @Autowired
    private ApplicationContext context;
    @Autowired
    private DatabaseClient db;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private InventoryService inventory;

    private WebTestClient client;
    private String admin;
    private String manager;
    private String finance;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToApplicationContext(context).build();
        run("TRUNCATE audit_log, stock_movements, inventory, products, suppliers, refresh_tokens, users, stores CASCADE");
        run("INSERT INTO stores (id, name, city, state, opened_on) VALUES ('S01','Whitefield','Bengaluru','Karnataka','2019-03-15'), ('S02','Madhapur','Hyderabad','Telangana','2020-04-15')");
        db.sql("""
                INSERT INTO users (id, name, email, role, store_id, active, password_hash) VALUES
                  ('U01','Rohit Menon','rohit@test.example','ADMIN',NULL,true,:h),
                  ('U03','Arjun Rao','arjun@test.example','STORE_MANAGER','S01',true,:h),
                  ('U10','Meera Joshi','meera@test.example','FINANCE_APPROVER',NULL,true,:h)
                """).bind("h", passwordEncoder.encode(PASSWORD)).then().block(Duration.ofSeconds(10));
        run("INSERT INTO suppliers (id, name) VALUES ('SUP23','Supplier SUP23')");
        run("""
            INSERT INTO products (sku, name, category, price_inr, cost_price_inr, gst_rate, supplier_id, status, reorder_level, catalogued_on, source) VALUES
              ('SW-SNK-00091','Smart Buy Potato Chips 150g','Snacks',25,20,12,'SUP23','ACTIVE',25,'2025-12-02','IMPORT'),
              ('SW-STA-00036','Sticky Notes','Stationery',40,30,18,'SUP23','ACTIVE',20,'2025-01-01','IMPORT'),
              ('SW-SNK-00064','Fresh Basket Chocolate Bar 100g','Snacks',52,42,12,'SUP23','ARCHIVED',25,'2025-04-20','IMPORT')
            """);
        run("""
            INSERT INTO inventory (store_id, sku, quantity, reorder_level, recount_needed) VALUES
              ('S01','SW-SNK-00091',37,25,false),
              ('S01','SW-STA-00036',0,20,true),
              ('S01','SW-SNK-00064',81,25,false),
              ('S02','SW-SNK-00091',94,25,false)
            """);
        admin = login("rohit@test.example");
        manager = login("arjun@test.example");
        finance = login("meera@test.example");
    }

    // --- reading -----------------------------------------------------------

    @Test
    void storeManagerSeesOnlyTheirOwnStore() {
        get(manager, "/api/inventory").jsonPath("$.totalItems").isEqualTo(3);   // S01 only
        client.get().uri("/api/inventory?storeId=S02").headers(h -> h.setBearerAuth(manager))
                .exchange().expectStatus().isForbidden();
        client.get().uri("/api/inventory/S02/SW-SNK-00091").headers(h -> h.setBearerAuth(manager))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void headOfficeSeesEveryStoreAndCanFilterByOne() {
        get(admin, "/api/inventory").jsonPath("$.totalItems").isEqualTo(4);
        get(finance, "/api/inventory?storeId=S02").jsonPath("$.totalItems").isEqualTo(1);
    }

    @Test
    void filtersLowStockAndRecountNeeded_D5_D7() {
        get(manager, "/api/inventory?lowOnly=true").jsonPath("$.items[0].sku").isEqualTo("SW-STA-00036").jsonPath("$.totalItems").isEqualTo(1);
        get(manager, "/api/inventory?recountOnly=true").jsonPath("$.items[0].recountNeeded").isEqualTo(true);
    }

    // --- adjustments -------------------------------------------------------

    @Test
    void damagedStockIsRemovedAndRecordedWithItsReason() {
        adjust(manager, "S01", "SW-SNK-00091", Map.of("type", "DAMAGED", "quantity", 2, "note", "Packets torn"), 200)
                .jsonPath("$.item.quantity").isEqualTo(35)
                .jsonPath("$.movement.delta").isEqualTo(-2)
                .jsonPath("$.movement.reason").isEqualTo("ADJUSTMENT_DAMAGED")
                .jsonPath("$.movement.actorUserId").isEqualTo("U03");
        get(manager, "/api/inventory/S01/SW-SNK-00091/history").jsonPath("$[0].quantityAfter").isEqualTo(35);
    }

    @Test
    void anAdjustmentThatWouldGoBelowZeroIsRefusedAndChangesNothing() {
        adjust(manager, "S01", "SW-SNK-00091", Map.of("type", "DAMAGED", "quantity", 38), 409);
        assertThat(number("SELECT quantity FROM inventory WHERE store_id = 'S01' AND sku = 'SW-SNK-00091'")).isEqualTo(37);
        assertThat(number("SELECT count(*) FROM stock_movements")).isZero();
    }

    @Test
    void recountSetsTheCountedQuantityAndClearsTheFlag_D5() {
        adjust(manager, "S01", "SW-STA-00036", Map.of("type", "RECOUNT", "quantity", 7), 200)
                .jsonPath("$.item.quantity").isEqualTo(7)
                .jsonPath("$.item.recountNeeded").isEqualTo(false)
                .jsonPath("$.movement.delta").isEqualTo(7)
                .jsonPath("$.movement.reason").isEqualTo("ADJUSTMENT_RECOUNT");
    }

    @Test
    void anOtherAdjustmentNeedsANote() {
        adjust(manager, "S01", "SW-SNK-00091", Map.of("type", "OTHER", "quantity", 3), 400);
        adjust(manager, "S01", "SW-SNK-00091", Map.of("type", "OTHER", "quantity", 3, "note", "Found in back room"), 200)
                .jsonPath("$.item.quantity").isEqualTo(40);
    }

    @Test
    void onlyTheStoresManagerOrAnAdminCanAdjust() {
        adjust(finance, "S01", "SW-SNK-00091", Map.of("type", "DAMAGED", "quantity", 1), 403);
        adjust(manager, "S02", "SW-SNK-00091", Map.of("type", "DAMAGED", "quantity", 1), 403);
        adjust(admin, "S02", "SW-SNK-00091", Map.of("type", "DAMAGED", "quantity", 1), 200);
    }

    @Test
    void archivedProductsCanStillBeWrittenOff_D3() {
        adjust(manager, "S01", "SW-SNK-00064", Map.of("type", "DAMAGED", "quantity", 81, "note", "Discontinued, disposed"), 200)
                .jsonPath("$.item.quantity").isEqualTo(0);
    }

    // --- M12: stock stays correct under overlapping changes ----------------

    /**
     * Ten write-offs of 1 unit hit a product with 5 in stock at the same moment.
     * Exactly 5 succeed, stock ends at 0 (never negative), and the history has exactly 5 entries
     * with consecutive "quantity after" values, so no change was lost or double-counted.
     */
    @Test
    void overlappingAdjustmentsNeverOversellOrLoseAChange_M12() {
        run("UPDATE inventory SET quantity = 5 WHERE store_id = 'S01' AND sku = 'SW-SNK-00091'");
        UserAccount arjun = new UserAccount("U03", "Arjun Rao", "arjun@test.example", UserAccount.Role.STORE_MANAGER, "S01", true, null);

        List<String> outcomes = Flux.range(0, 10)
                .flatMap(i -> inventory.adjust(arjun, "S01", "SW-SNK-00091", new AdjustmentRequest("DAMAGED", 1, "test " + i))
                        .map(r -> "ok").onErrorReturn("refused"), 10)
                .collectList().block(Duration.ofSeconds(30));

        assertThat(outcomes).filteredOn("ok"::equals).hasSize(5);
        assertThat(outcomes).filteredOn("refused"::equals).hasSize(5);
        assertThat(number("SELECT quantity FROM inventory WHERE store_id = 'S01' AND sku = 'SW-SNK-00091'")).isZero();
        List<Integer> after = db.sql("SELECT quantity_after FROM stock_movements WHERE sku = 'SW-SNK-00091' ORDER BY quantity_after DESC")
                .map((row, meta) -> row.get(0, Integer.class)).all().collectList().block(Duration.ofSeconds(10));
        assertThat(after).containsExactly(4, 3, 2, 1, 0);
    }

    // --- helpers -----------------------------------------------------------

    private WebTestClient.BodyContentSpec adjust(String token, String store, String sku, Map<String, Object> body, int status) {
        return client.post().uri("/api/inventory/{store}/{sku}/adjustments", store, sku).headers(h -> h.setBearerAuth(token))
                .bodyValue(body).exchange().expectStatus().isEqualTo(status).expectBody();
    }

    private WebTestClient.BodyContentSpec get(String token, String uri) {
        return client.get().uri(uri).headers(h -> h.setBearerAuth(token)).exchange().expectStatus().isOk().expectBody();
    }

    private String login(String email) {
        return client.post().uri("/api/auth/login").bodyValue(Map.of("email", email, "password", PASSWORD))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody().get("accessToken").toString();
    }

    private long number(String sql) {
        return db.sql(sql).map((row, meta) -> ((Number) row.get(0)).longValue()).one().block(Duration.ofSeconds(10));
    }

    private void run(String sql) {
        db.sql(sql).then().block(Duration.ofSeconds(10));
    }
}
