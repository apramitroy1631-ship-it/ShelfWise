package com.shelfwise.core.catalogue;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
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
import com.shelfwise.core.catalogue.CatalogueDtos.UpdateProductRequest;

import reactor.core.publisher.Mono;

/** Catalogue API end to end (M09): browse for everyone, changes for admins only. */
@SpringBootTest
@Import(TestDatabaseConfig.class)
class CatalogueApiTest {

    private static final String PASSWORD = "Test-Only-Pass-1";

    @Autowired
    private ApplicationContext context;
    @Autowired
    private DatabaseClient db;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private CatalogueService catalogue;

    private WebTestClient client;
    private String adminToken;
    private String managerToken;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToApplicationContext(context).build();
        run("TRUNCATE audit_log, inventory, products, suppliers, refresh_tokens, users, stores CASCADE");
        run("INSERT INTO stores (id, name, city, state, opened_on) VALUES ('S01','Whitefield','Bengaluru','Karnataka','2019-03-15')");
        db.sql("""
                INSERT INTO users (id, name, email, role, store_id, active, password_hash) VALUES
                  ('U01','Rohit Menon','rohit@test.example','ADMIN',NULL,true,:h),
                  ('U03','Arjun Rao','arjun@test.example','STORE_MANAGER','S01',true,:h)
                """).bind("h", passwordEncoder.encode(PASSWORD)).then().block(Duration.ofSeconds(10));
        run("INSERT INTO suppliers (id, name) VALUES ('SUP15','Supplier SUP15'), ('SUP23','Supplier SUP23')");
        run("INSERT INTO suppliers (id, name, active) VALUES ('SUP99','Supplier SUP99', false)");
        run("""
            INSERT INTO products (sku, name, category, price_inr, cost_price_inr, gst_rate, supplier_id, status, reorder_level, catalogued_on, source) VALUES
              ('SW-HOM-00033','Everyday Dishwash Liquid 250ml','Household',96,75,18,'SUP15','ACTIVE',8,'2025-01-01','IMPORT'),
              ('sw-hom-00033','Everyday Dishwash Liquid 250ml','Household',99,75,18,'SUP15','ACTIVE',8,'2025-01-01','IMPORT'),
              ('SW-SNK-00091','Smart Buy Potato Chips 150g','Snacks',25,20,12,'SUP23','ACTIVE',25,'2025-12-02','IMPORT'),
              ('SW-BEV-00005','Green Leaf Instant Coffee 50g','Beverages',NULL,169,12,'SUP23','ACTIVE',10,'2025-01-01','IMPORT'),
              ('SW-SNK-00064','Fresh Basket Chocolate Bar 100g','Snacks',52,42,12,'SUP23','ARCHIVED',25,'2025-04-20','IMPORT'),
              ('SW-STA-00001','Value Pack Sticky Notes 50% off pack','Stationery',40,30,18,'SUP23','ACTIVE',5,'2025-01-01','IMPORT')
            """);
        adminToken = login("rohit@test.example");
        managerToken = login("arjun@test.example");
    }

    // --- browse (everyone signed in) ---------------------------------------

    @Test
    void browsingShowsActiveProductsByDefaultWithPaging() {
        get(managerToken, "/api/products?size=2&page=0&sort=sku")
                .jsonPath("$.totalItems").isEqualTo(5)          // the archived chocolate bar is hidden (D3)
                .jsonPath("$.totalPages").isEqualTo(3)
                .jsonPath("$.items.length()").isEqualTo(2)
                .jsonPath("$.items[0].sku").isEqualTo("SW-BEV-00005");
    }

    @Test
    void searchMatchesNameOrSkuIgnoringCase() {
        get(managerToken, "/api/products?q=dishwash").jsonPath("$.totalItems").isEqualTo(2);
        get(managerToken, "/api/products?q=snk-00091").jsonPath("$.items[0].sku").isEqualTo("SW-SNK-00091");
    }

    @Test
    void searchTreatsPercentAsAPlainCharacter() {
        client.get().uri(b -> b.path("/api/products").queryParam("q", "50%").build())   // "50%" matches only "50% off"
                .headers(h -> h.setBearerAuth(managerToken))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.totalItems").isEqualTo(1);
    }

    @Test
    void filtersByCategoryAndStatus() {
        get(managerToken, "/api/products?category=Snacks").jsonPath("$.totalItems").isEqualTo(1);
        get(managerToken, "/api/products?status=ARCHIVED").jsonPath("$.items[0].sku").isEqualTo("SW-SNK-00064");
        get(managerToken, "/api/products?status=ALL").jsonPath("$.totalItems").isEqualTo(6);
    }

    @Test
    void sortsByPriceWithUnpricedProductsLast() {
        get(managerToken, "/api/products?sort=price&direction=desc&size=10")
                .jsonPath("$.items[0].sku").isEqualTo("sw-hom-00033")      // 99
                .jsonPath("$.items[4].sku").isEqualTo("SW-BEV-00005");     // no price, last
    }

    @Test
    void rejectsUnknownSortAndOversizedPages() {
        client.get().uri("/api/products?sort=password_hash").headers(h -> h.setBearerAuth(managerToken))
                .exchange().expectStatus().isBadRequest();
        client.get().uri("/api/products?size=500").headers(h -> h.setBearerAuth(managerToken))
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    void skuLookupIsCaseSensitive_D1() {
        get(managerToken, "/api/products/SW-HOM-00033").jsonPath("$.priceInr").isEqualTo(96);
        get(managerToken, "/api/products/sw-hom-00033").jsonPath("$.priceInr").isEqualTo(99);
        client.get().uri("/api/products/SW-NOPE-00000").headers(h -> h.setBearerAuth(managerToken))
                .exchange().expectStatus().isNotFound();
    }

    @Test
    void unpricedProductsAreShownAsNotSellable_D2() {
        get(managerToken, "/api/products/SW-BEV-00005")
                .jsonPath("$.priceInr").doesNotExist()
                .jsonPath("$.sellable").isEqualTo(false);
    }

    @Test
    void browsingNeedsSignIn() {
        client.get().uri("/api/products").exchange().expectStatus().isUnauthorized();
    }

    // --- changes (admins only) ---------------------------------------------

    @Test
    void storeManagersCannotChangeTheCatalogue() {
        client.post().uri("/api/products").headers(h -> h.setBearerAuth(managerToken)).bodyValue(newProduct("SW-SNK-00999"))
                .exchange().expectStatus().isForbidden();
        client.post().uri("/api/products/SW-SNK-00091/archive").headers(h -> h.setBearerAuth(managerToken))
                .bodyValue(Map.of("version", 0)).exchange().expectStatus().isForbidden();
    }

    @Test
    void adminCreatesAProductAndItIsAudited() {
        client.post().uri("/api/products").headers(h -> h.setBearerAuth(adminToken)).bodyValue(newProduct("SW-SNK-00999"))
                .exchange().expectStatus().isCreated()
                .expectBody().jsonPath("$.sellable").isEqualTo(true).jsonPath("$.version").isEqualTo(0);
        assertThat(count("SELECT count(*) FROM products WHERE sku = 'SW-SNK-00999' AND source = 'APP'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_log WHERE action = 'PRODUCT_CREATED' AND actor_user_id = 'U01'")).isEqualTo(1);
    }

    @Test
    void newProductsMustHaveAPriceACleanSkuAndAnActiveSupplier() {
        Map<String, Object> noPrice = new java.util.HashMap<>(newProduct("SW-SNK-00998"));
        noPrice.remove("priceInr");
        expectStatus(adminToken, "/api/products", noPrice, 400);                                   // D2
        expectStatus(adminToken, "/api/products", newProduct("sw-snk-00998"), 400);                // not the clean format (D1)
        Map<String, Object> inactiveSupplier = new java.util.HashMap<>(newProduct("SW-SNK-00998"));
        inactiveSupplier.put("supplierId", "SUP99");
        expectStatus(adminToken, "/api/products", inactiveSupplier, 400);
    }

    @Test
    void aSkuThatDiffersOnlyByCaseIsRefused_D1() {
        run("INSERT INTO products (sku, name, category, price_inr, cost_price_inr, gst_rate, supplier_id, status, reorder_level, catalogued_on, source) "
                + "VALUES ('sw-snk-00500','Old variant','Snacks',10,5,5,'SUP23','ACTIVE',5,'2025-01-01','IMPORT')");
        expectStatus(adminToken, "/api/products", newProduct("SW-SNK-00500"), 409);
    }

    @Test
    void adminSetsAPriceOnAnUnpricedProduct_D2() {
        client.put().uri("/api/products/SW-BEV-00005").headers(h -> h.setBearerAuth(adminToken))
                .bodyValue(update("Green Leaf Instant Coffee 50g", new BigDecimal("199"), 0))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.sellable").isEqualTo(true).jsonPath("$.version").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_log WHERE action = 'PRODUCT_PRICE_SET'")).isEqualTo(1);
    }

    @Test
    void aPriceCanBeChangedButNotRemoved() {
        expectPut("/api/products/SW-SNK-00091", update("Smart Buy Potato Chips 150g", null, 0), 400);
    }

    /** Two admins edit from the same version: the second save is refused instead of silently overwriting. */
    @Test
    void anEditFromAStaleVersionIsRefused() {
        expectPut("/api/products/SW-SNK-00091", update("Chips (first admin)", new BigDecimal("26"), 0), 200);
        expectPut("/api/products/SW-SNK-00091", update("Chips (second admin)", new BigDecimal("27"), 0), 409);
        get(managerToken, "/api/products/SW-SNK-00091").jsonPath("$.name").isEqualTo("Chips (first admin)");
    }

    /** The same race, truly at the same moment: exactly one of two simultaneous edits wins. */
    @Test
    void simultaneousEditsLetExactlyOneWin() {
        Mono<String> first = catalogue.update("SW-SNK-00091", update("A", new BigDecimal("30"), 0), "U01")
                .map(p -> "saved").onErrorReturn("refused");
        Mono<String> second = catalogue.update("SW-SNK-00091", update("B", new BigDecimal("31"), 0), "U01")
                .map(p -> "saved").onErrorReturn("refused");

        List<String> outcomes = Mono.zip(first, second).map(t -> List.of(t.getT1(), t.getT2())).block(Duration.ofSeconds(20));

        assertThat(outcomes).containsExactlyInAnyOrder("saved", "refused");
        assertThat(count("SELECT version FROM products WHERE sku = 'SW-SNK-00091'")).isEqualTo(1);
    }

    @Test
    void archiveAndUnarchiveAreAuditedAndCannotBeRepeated_D3() {
        expectPost("/api/products/SW-SNK-00091/archive", Map.of("version", 0, "reason", "Discontinued"), 200);
        get(managerToken, "/api/products?q=chips").jsonPath("$.totalItems").isEqualTo(0);       // hidden from browsing
        get(managerToken, "/api/products/SW-SNK-00091").jsonPath("$.sellable").isEqualTo(false);
        expectPost("/api/products/SW-SNK-00091/archive", Map.of("version", 1), 409);             // already archived

        expectPost("/api/products/SW-SNK-00091/unarchive", Map.of("version", 1), 200);
        get(managerToken, "/api/products/SW-SNK-00091")
                .jsonPath("$.status").isEqualTo("ACTIVE")
                .jsonPath("$.priceInr").isEqualTo(25);                                            // the price to confirm
        assertThat(count("SELECT count(*) FROM audit_log WHERE entity_id = 'SW-SNK-00091' AND action IN ('PRODUCT_ARCHIVED','PRODUCT_UNARCHIVED')"))
                .isEqualTo(2);
    }

    // --- helpers -----------------------------------------------------------

    private static Map<String, Object> newProduct(String sku) {
        return Map.of("sku", sku, "name", "Test Crisps 100g", "category", "Snacks", "priceInr", 30,
                "costPriceInr", 20, "gstRate", 12, "supplierId", "SUP23", "reorderLevel", 10);
    }

    private static UpdateProductRequest update(String name, BigDecimal price, long version) {
        return new UpdateProductRequest(name, "Snacks", price, new BigDecimal("20"), new BigDecimal("12"), "SUP23", 25, version);
    }

    private WebTestClient.BodyContentSpec get(String token, String uri) {
        return client.get().uri(uri).headers(h -> h.setBearerAuth(token)).exchange().expectStatus().isOk().expectBody();
    }

    private void expectStatus(String token, String uri, Object body, int status) {
        client.post().uri(uri).headers(h -> h.setBearerAuth(token)).bodyValue(body).exchange().expectStatus().isEqualTo(status);
    }

    private void expectPost(String uri, Object body, int status) {
        expectStatus(adminToken, uri, body, status);
    }

    private void expectPut(String uri, Object body, int status) {
        client.put().uri(uri).headers(h -> h.setBearerAuth(adminToken)).bodyValue(body).exchange().expectStatus().isEqualTo(status);
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
        db.sql(sql).then().block(Duration.ofSeconds(10));
    }
}
