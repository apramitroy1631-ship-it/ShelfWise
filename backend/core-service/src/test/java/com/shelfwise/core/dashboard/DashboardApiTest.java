package com.shelfwise.core.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.shelfwise.core.TestDatabaseConfig;
import com.shelfwise.core.dataimport.DataImportService;

/**
 * Dashboard and CSV export (M22) on the REAL provided data, imported once. The expected figures are the ones
 * agreed in docs/data-findings.md, so this test also proves the dashboard agrees with the import decisions.
 */
@SpringBootTest(properties = {
        "shelfwise.import.data-dir=../../data",
        "shelfwise.import.demo-password=" + DashboardApiTest.PASSWORD
})
@Import(TestDatabaseConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DashboardApiTest {

    static final String PASSWORD = "Test-Only-Demo-1";

    @Autowired
    private DataImportService importService;
    @Autowired
    private DatabaseClient db;
    @Autowired
    private ApplicationContext context;

    private WebTestClient client;
    private String admin;
    private String arjun;
    private String finance;

    @BeforeAll
    void importTheProvidedData() {
        db.sql("""
                TRUNCATE import_issues, raw_import_records, import_runs, po_approvals, po_lines, purchase_orders,
                         order_lines, customer_orders, stock_movements, inventory, products,
                         suppliers, refresh_tokens, users, stores CASCADE
                """).then().block(Duration.ofSeconds(30));
        importService.runInsertOnly().block(Duration.ofMinutes(5));
        client = WebTestClient.bindToApplicationContext(context).configureClient().responseTimeout(Duration.ofSeconds(60))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024)).build();   // the full CSV is ~1 MB
        admin = login("rohit.menon@shelfwise.example");
        arjun = login("arjun.rao@shelfwise.example");
        finance = login("meera.joshi@shelfwise.example");
    }

    // --- figures ---------------------------------------------------------------------

    /** User decision 2026-11-11: revenue = delivered orders only; flagged orders wait for review (D10). */
    @Test
    void revenueIsDeliveredOrdersOnlyAndMatchesTheImportDecisions() {
        Map<?, ?> all = dashboard(admin, "");

        assertThat(number(all.get("revenue"))).isEqualTo(14_538_055L);          // ₹1,45,38,055 (D8, D10, D11)
        assertThat(number(all.get("deliveredOrders")))
                .isEqualTo(count("SELECT count(*) FROM customer_orders WHERE status = 'DELIVERED' AND review_status IS NULL"));
    }

    @Test
    void theOrderCountIncludesEveryStatusAndLeavesOutOrdersWaitingForReview_D10() {
        Map<?, ?> all = dashboard(admin, "");

        assertThat(number(all.get("orderCount"))).isEqualTo(14_960L - 15);     // 15 imported orders wait for review
        Map<?, ?> byStatus = (Map<?, ?>) all.get("ordersByStatus");
        assertThat(byStatus.keySet().stream().map(Object::toString).toList()).containsExactly("PENDING", "PACKED", "SHIPPED", "DELIVERED", "CANCELLED");
        assertThat(byStatus.values().stream().mapToLong(DashboardApiTest::number).sum()).isEqualTo(number(all.get("orderCount")));
    }

    @Test
    void topProductsAreRankedByRevenueWithUnitsAndUnknownProductsSeparate_D12() {
        Map<?, ?> all = dashboard(admin, "");

        List<?> top = (List<?>) all.get("topProducts");
        assertThat(top).hasSize(10);
        List<Long> revenues = top.stream().map(p -> number(((Map<?, ?>) p).get("revenue"))).toList();
        assertThat(revenues).isSortedAccordingTo((a, b) -> Long.compare(b, a));
        assertThat(number(((Map<?, ?>) top.get(0)).get("units"))).isPositive();
        assertThat(number(((Map<?, ?>) all.get("unknownProducts")).get("revenue"))).isEqualTo(15_994L);   // D12
    }

    @Test
    void lowStockCountMatchesD7() {
        Map<?, ?> all = dashboard(admin, "");

        assertThat(number(all.get("lowStockCount"))).isEqualTo(1_046L);
        assertThat(number(all.get("outOfStockCount"))).isEqualTo(190L);
    }

    /** The five stores add up to the whole chain, and the trend adds up to the total. */
    @Test
    void storesAndTrendAddUpToTheTotal() {
        long total = number(dashboard(admin, "").get("revenue"));
        long byStore = 0;
        for (String s : List.of("S01", "S02", "S03", "S04", "S05")) {
            byStore += number(dashboard(finance, "storeId=" + s).get("revenue"));
        }
        assertThat(byStore).isEqualTo(total);

        Map<?, ?> october = dashboard(admin, "from=2026-10-01&to=2026-10-31");
        assertThat(october.get("trendBy")).isEqualTo("day");
        List<?> trend = (List<?>) october.get("trend");
        assertThat(trend).isNotEmpty();
        assertThat(trend.stream().mapToLong(p -> number(((Map<?, ?>) p).get("revenue"))).sum()).isEqualTo(number(october.get("revenue")));
        assertThat(trend.stream().map(p -> ((Map<?, ?>) p).get("period").toString()))
                .allMatch(d -> d.compareTo("2026-10-01") >= 0 && d.compareTo("2026-10-31") <= 0);
        assertThat(dashboard(admin, "").get("trendBy")).isEqualTo("month");       // no range: monthly
    }

    /** IST days (D9): an order placed at 00:10 IST on 1 Oct belongs to 1 Oct, though it is still 30 Sep in UTC. */
    @Test
    void dateRangesAreIstDays_D9() {
        long expected = count("""
                SELECT count(*) FROM customer_orders WHERE review_status IS NULL
                  AND created_at >= '2026-10-01 00:00+05:30' AND created_at < '2026-11-01 00:00+05:30'""");
        assertThat(number(dashboard(admin, "from=2026-10-01&to=2026-10-31").get("orderCount"))).isEqualTo(expected);
    }

    @Test
    void managersSeeOnlyTheirOwnStore() {
        Map<?, ?> own = dashboard(arjun, "");
        assertThat(own.get("storeId")).isEqualTo("S01");
        assertThat(number(own.get("revenue"))).isEqualTo(number(dashboard(admin, "storeId=S01").get("revenue")));
        client.get().uri("/api/dashboard?storeId=S02").headers(h -> h.setBearerAuth(arjun)).exchange().expectStatus().isForbidden();
    }

    @Test
    void badRangesAreRefused() {
        client.get().uri("/api/dashboard?from=2026-10-31&to=2026-10-01").headers(h -> h.setBearerAuth(admin)).exchange().expectStatus().isBadRequest();
        client.get().uri("/api/dashboard?trendBy=year").headers(h -> h.setBearerAuth(admin)).exchange().expectStatus().isBadRequest();
    }

    // --- CSV export --------------------------------------------------------------------

    @Test
    void theCsvHasEveryFilteredOrderWithMaskedPhones_D16() {
        String csv = client.get().uri("/api/orders/export.csv?storeId=S01&from=2026-10-01&to=2026-10-31")
                .headers(h -> h.setBearerAuth(admin)).exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith("text/csv")
                .expectHeader().valueEquals("Content-Disposition", "attachment; filename=\"orders-S01-from-2026-10-01-to-2026-10-31.csv\"")
                .expectBody(String.class).returnResult().getResponseBody();

        String[] lines = csv.replace("\uFEFF", "").split("\r\n");
        assertThat(lines[0]).isEqualTo("Order ID,Store,Status,Placed (IST),Customer,Customer phone,Units,Total (INR)");
        long expected = count("""
                SELECT count(*) FROM customer_orders WHERE store_id = 'S01' AND review_status IS NULL
                  AND created_at >= '2026-10-01 00:00+05:30' AND created_at < '2026-11-01 00:00+05:30'""");
        assertThat(lines.length - 1L).isEqualTo(expected);
        assertThat(csv).doesNotContainPattern("\\+91 \\d{10}");                   // never a full phone number
        assertThat(lines[1]).matches("ORD-\\d{6},S01,[A-Z]+,2026-10-\\d\\d \\d\\d:\\d\\d,.*,\\+91 \\d\\dXXXXX\\d{3},\\d+,[\\d.]+");
    }

    @Test
    void theCsvLeavesOutOrdersWaitingForReviewAndRespectsStoreScope_D10() {
        String flagged = db.sql("SELECT id FROM customer_orders WHERE review_status = 'NEEDS_REVIEW' LIMIT 1")
                .map((row, meta) -> row.get(0, String.class)).one().block(Duration.ofSeconds(10));
        String csv = client.get().uri("/api/orders/export.csv").headers(h -> h.setBearerAuth(admin)).exchange()
                .expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        assertThat(csv).doesNotContain(flagged + ",");

        client.get().uri("/api/orders/export.csv?storeId=S02").headers(h -> h.setBearerAuth(arjun)).exchange().expectStatus().isForbidden();
    }

    // --- helpers -------------------------------------------------------------------------

    private Map<?, ?> dashboard(String token, String query) {
        return client.get().uri("/api/dashboard" + (query.isEmpty() ? "" : "?" + query)).headers(h -> h.setBearerAuth(token))
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

    private static long number(Object value) {
        return Math.round(((Number) value).doubleValue());
    }
}
