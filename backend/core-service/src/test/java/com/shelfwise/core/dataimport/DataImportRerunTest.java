package com.shelfwise.core.dataimport;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.r2dbc.core.DatabaseClient;

import com.shelfwise.core.TestDatabaseConfig;

/** D28: running the import again adds only what's new and never overwrites changes made in the app. */
@SpringBootTest(properties = {
        "shelfwise.import.data-dir=../../data",
        "shelfwise.import.demo-password=Test-Only-Demo-1"
})
@Import(TestDatabaseConfig.class)
class DataImportRerunTest {

    @Autowired
    private DataImportService importService;
    @Autowired
    private DatabaseClient db;

    @BeforeEach
    void importOnce() {
        ImportTestSupport.emptyDatabase(db);
        runImport();
    }

    @Test
    void secondRunAddsNothingAndSkipsEverything_D28() {
        List<FileResult> second = runImport();

        assertThat(second).allSatisfy(result -> {
            assertThat(result.added()).isZero();
            assertThat(result.rejected()).isZero();
        });
        assertThat(second.stream().mapToInt(FileResult::skipped).sum()).isEqualTo(5 + 12 + 25 + 800 + 4000);
        assertThat(ImportTestSupport.number(db, "SELECT count(*) FROM products")).isEqualTo(800);
    }

    @Test
    void reRunNeverOverwritesChangesMadeInTheApp_D28() {
        execute("UPDATE users SET name = 'Changed in the app' WHERE id = 'U03'");
        execute("UPDATE products SET price_inr = 120 WHERE sku = 'SW-BEV-00005'");                  // admin set the D2 price
        execute("UPDATE products SET status = 'ACTIVE' WHERE sku = 'SW-SNK-00064'");                 // admin unarchived it (D3)
        execute("UPDATE inventory SET quantity = 7, recount_needed = false WHERE store_id = 'S03' AND sku = 'SW-STA-00036'");

        runImport();

        assertThat(text("SELECT name FROM users WHERE id = 'U03'")).isEqualTo("Changed in the app");
        assertThat(text("SELECT price_inr::text FROM products WHERE sku = 'SW-BEV-00005'")).isEqualTo("120.00");
        assertThat(text("SELECT status FROM products WHERE sku = 'SW-SNK-00064'")).isEqualTo("ACTIVE");
        assertThat(ImportTestSupport.number(db, "SELECT quantity FROM inventory WHERE store_id = 'S03' AND sku = 'SW-STA-00036'")).isEqualTo(7);
        // and no second import correction was recorded (D5)
        assertThat(ImportTestSupport.number(db, "SELECT count(*) FROM stock_movements WHERE reason = 'IMPORT_CORRECTION'")).isEqualTo(15);
    }

    private List<FileResult> runImport() {
        return importService.runInsertOnly().block(Duration.ofMinutes(5));
    }

    private void execute(String sql) {
        db.sql(sql).then().block(Duration.ofSeconds(10));
    }

    private String text(String sql) {
        return db.sql(sql).map((row, meta) -> String.valueOf(row.get(0))).one().block(Duration.ofSeconds(10));
    }
}
