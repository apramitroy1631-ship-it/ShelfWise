package com.shelfwise.core.dataimport;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

/**
 * Imports the real provided files (../../data) once into an empty database,
 * then checks every decision applied. Expected numbers come from docs/data-findings.md.
 */
@SpringBootTest(properties = {
        "shelfwise.import.data-dir=../../data",
        "shelfwise.import.demo-password=" + DataImportTest.DEMO_PASSWORD
})
@Import(TestDatabaseConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DataImportTest {

    static final String DEMO_PASSWORD = "Test-Only-Demo-1";

    @Autowired
    private DataImportService importService;
    @Autowired
    private DatabaseClient db;
    @Autowired
    private ApplicationContext context;

    private Map<String, FileResult> results;

    @BeforeAll
    void importOnceIntoAnEmptyDatabase() {
        ImportTestSupport.emptyDatabase(db);
        List<FileResult> list = importService.runInsertOnly().block(Duration.ofMinutes(5));
        results = list.stream().collect(Collectors.toMap(FileResult::file, r -> r));
    }

    // --- counts per file ---------------------------------------------------

    @Test
    void everyRowOfEveryFileIsImportedAndNothingIsRejected() {
        assertThat(results.get("stores.csv")).isEqualTo(new FileResult("stores.csv", 5, 0, 0));
        assertThat(results.get("users.csv")).isEqualTo(new FileResult("users.csv", 12, 0, 0));
        assertThat(results.get(CatalogueImporter.SUPPLIERS)).isEqualTo(new FileResult(CatalogueImporter.SUPPLIERS, 25, 0, 0));
        assertThat(results.get("products.csv")).isEqualTo(new FileResult("products.csv", 800, 0, 0));
        assertThat(results.get("inventory.csv")).isEqualTo(new FileResult("inventory.csv", 4000, 0, 0));
    }

    @Test
    void theOriginalOfEveryAddedRecordIsKept() {
        assertThat(count("SELECT count(*) FROM raw_import_records")).isEqualTo(5 + 12 + 800 + 4000);
        // the trailing space of a variant SKU survives in the kept original
        assertThat(count("SELECT count(*) FROM raw_import_records WHERE payload->>'sku' = 'SW-SNK-00091 '")).isEqualTo(6);
    }

    // --- stores and users --------------------------------------------------

    @Test
    void inactiveUserStaysInactive_D20_D27() {
        assertThat(text("SELECT active::text FROM users WHERE id = 'U12'")).isEqualTo("false");
        assertThat(issues("D27")).isEqualTo(1);
    }

    @Test
    void storesWithTwoManagersKeepTheFileManagerAsMainContact_D26() {
        assertThat(text("SELECT main_contact_user_id FROM stores WHERE id = 'S01'")).isEqualTo("U03");
        assertThat(count("SELECT count(*) FROM users WHERE store_id = 'S01' AND role = 'STORE_MANAGER'")).isEqualTo(2);
        assertThat(issues("D26")).isEqualTo(2);   // S01 and S02
    }

    @Test
    void passwordsAreStoredHashedNeverInPlainText_D25() {
        assertThat(count("SELECT count(*) FROM users WHERE password_hash LIKE '$2%'")).isEqualTo(12);
        assertThat(count("SELECT count(*) FROM users WHERE password_hash = '" + DEMO_PASSWORD + "'")).isZero();
    }

    /** PIP objective 1 with the real data: each role signs in with the demo password; the inactive user can't. */
    @Test
    void importedUsersCanSignInExceptTheInactiveOne() {
        WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
        for (String email : List.of("rohit.menon@shelfwise.example", "arjun.rao@shelfwise.example", "meera.joshi@shelfwise.example")) {
            client.post().uri("/api/auth/login").bodyValue(Map.of("email", email, "password", DEMO_PASSWORD))
                    .exchange().expectStatus().isOk();
        }
        client.post().uri("/api/auth/login").bodyValue(Map.of("email", "pooja.bansal@shelfwise.example", "password", DEMO_PASSWORD))
                .exchange().expectStatus().isUnauthorized();
    }

    // --- suppliers and products --------------------------------------------

    @Test
    void suppliersAreCreatedFromTheirIds_D24() {
        assertThat(text("SELECT name FROM suppliers WHERE id = 'SUP23'")).isEqualTo("Supplier SUP23");
        assertThat(issues("D24")).isEqualTo(25);
    }

    @Test
    void variantSkusAreSeparateLowercaseProducts_D1() {
        assertThat(count("SELECT count(*) FROM products")).isEqualTo(800);
        assertThat(text("SELECT price_inr::text FROM products WHERE sku = 'SW-SNK-00091'")).isEqualTo("25.00");
        assertThat(text("SELECT price_inr::text FROM products WHERE sku = 'sw-snk-00091'")).isEqualTo("28.00");   // was 'SW-SNK-00091 '
        assertThat(count("SELECT count(*) FROM products WHERE sku <> upper(sku)")).isEqualTo(12);
        assertThat(issues("D1")).isEqualTo(12);
    }

    @Test
    void productsWithoutAPriceAreImportedAndFlagged_D2() {
        assertThat(count("SELECT count(*) FROM products WHERE price_inr IS NULL")).isEqualTo(10);
        assertThat(text("SELECT price_inr IS NULL FROM products WHERE sku = 'SW-BEV-00005'")).isEqualTo("true");
        assertThat(count("SELECT count(*) FROM import_issues WHERE decision_code = 'D2' AND outcome = 'FLAGGED'")).isEqualTo(10);
    }

    @Test
    void archivedProductsKeepTheirStatus_D3() {
        assertThat(count("SELECT count(*) FROM products WHERE status = 'ARCHIVED'")).isEqualTo(25);
        assertThat(issues("D3")).isEqualTo(25);
    }

    // --- inventory ---------------------------------------------------------

    @Test
    void variantStockRowsBelongToTheVariantProduct_D1_D4() {
        // inventory.csv: S04 'SW-ELE-00079' = 18 and S04 'SW-ELE-00079 ' = 6
        assertThat(count("SELECT quantity FROM inventory WHERE store_id = 'S04' AND sku = 'SW-ELE-00079'")).isEqualTo(18);
        assertThat(count("SELECT quantity FROM inventory WHERE store_id = 'S04' AND sku = 'sw-ele-00079'")).isEqualTo(6);
        assertThat(count("SELECT count(*) FROM inventory")).isEqualTo(4000);
    }

    @Test
    void negativeStockBecomesZeroWithACorrectionInHistory_D5() {
        // inventory.csv line 196: S03 SW-STA-00036 = -10
        assertThat(count("SELECT quantity FROM inventory WHERE store_id = 'S03' AND sku = 'SW-STA-00036'")).isZero();
        assertThat(text("SELECT recount_needed::text FROM inventory WHERE store_id = 'S03' AND sku = 'SW-STA-00036'")).isEqualTo("true");
        assertThat(count("SELECT delta FROM stock_movements WHERE store_id = 'S03' AND sku = 'SW-STA-00036' AND reason = 'IMPORT_CORRECTION'"))
                .isEqualTo(10);
        assertThat(count("SELECT count(*) FROM inventory WHERE recount_needed")).isEqualTo(15);
        assertThat(issues("D5")).isEqualTo(15);
    }

    @Test
    void everyCountDateIsReadAsAnExactMoment_D6() {
        assertThat(count("SELECT count(*) FROM inventory WHERE last_counted_at IS NULL")).isZero();
        // inventory.csv line 4: S02 SW-ELE-00072 counted at epoch 1792477631000 = 2026-10-20 11:57:11 IST
        OffsetDateTime counted = db.sql("SELECT last_counted_at FROM inventory WHERE store_id = 'S02' AND sku = 'SW-ELE-00072'")
                .map((row, meta) -> row.get(0, OffsetDateTime.class)).one().block(Duration.ofSeconds(10));
        assertThat(counted.toInstant()).isEqualTo(OffsetDateTime.parse("2026-10-20T11:57:11+05:30").toInstant());
        // line 2: '16/10/2026' is day-first: 16 October
        OffsetDateTime dayFirst = db.sql("SELECT last_counted_at FROM inventory WHERE store_id = 'S01' AND sku = 'SW-GRO-00036'")
                .map((row, meta) -> row.get(0, OffsetDateTime.class)).one().block(Duration.ofSeconds(10));
        assertThat(dayFirst.toInstant()).isEqualTo(OffsetDateTime.parse("2026-10-16T00:00+05:30").toInstant());
    }

    @Test
    void lowStockRowsMatchTheFindings_D7() {
        assertThat(count("SELECT count(*) FROM inventory WHERE quantity <= reorder_level")).isEqualTo(1073);
    }

    // --- helpers -----------------------------------------------------------

    private long issues(String decision) {
        return count("SELECT count(*) FROM import_issues WHERE decision_code = '" + decision + "'");
    }

    private long count(String sql) {
        return ImportTestSupport.number(db, sql);
    }

    private String text(String sql) {
        return db.sql(sql).map((row, meta) -> String.valueOf(row.get(0))).one().block(Duration.ofSeconds(10));
    }
}
