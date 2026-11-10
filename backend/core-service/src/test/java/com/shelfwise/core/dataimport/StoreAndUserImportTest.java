package com.shelfwise.core.dataimport;

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
import org.springframework.test.web.reactive.server.WebTestClient;

import com.shelfwise.core.TestDatabaseConfig;

/** Imports the real provided files (../../data) into a throwaway database. */
@SpringBootTest(properties = {
        "shelfwise.import.data-dir=../../data",
        "shelfwise.import.demo-password=" + StoreAndUserImportTest.DEMO_PASSWORD
})
@Import(TestDatabaseConfig.class)
class StoreAndUserImportTest {

    static final String DEMO_PASSWORD = "Test-Only-Demo-1";

    @Autowired
    private DataImportService importService;
    @Autowired
    private DatabaseClient db;
    @Autowired
    private ApplicationContext context;

    @BeforeEach
    void emptyDatabase() {
        db.sql("TRUNCATE import_issues, raw_import_records, import_runs, refresh_tokens, users, stores CASCADE")
                .then().block(Duration.ofSeconds(10));
    }

    @Test
    void importsAllFiveStoresAndTwelveUsers() {
        Map<String, FileResult> results = runImport();

        assertThat(results.get("stores.csv")).isEqualTo(new FileResult("stores.csv", 5, 0, 0));
        assertThat(results.get("users.csv")).isEqualTo(new FileResult("users.csv", 12, 0, 0));
        assertThat(count("SELECT count(*) FROM users WHERE role = 'STORE_MANAGER'")).isEqualTo(7);
        assertThat(count("SELECT count(*) FROM raw_import_records")).isEqualTo(17);   // the originals are kept
    }

    @Test
    void inactiveUserStaysInactive_D20_D27() {
        runImport();

        assertThat(text("SELECT active::text FROM users WHERE id = 'U12'")).isEqualTo("false");
        assertThat(count("SELECT count(*) FROM import_issues WHERE decision_code = 'D27' AND entity_id = 'U12'")).isEqualTo(1);
    }

    @Test
    void storesWithTwoManagersKeepTheFileManagerAsMainContact_D26() {
        runImport();

        assertThat(text("SELECT main_contact_user_id FROM stores WHERE id = 'S01'")).isEqualTo("U03");
        assertThat(count("SELECT count(*) FROM users WHERE store_id = 'S01' AND role = 'STORE_MANAGER'")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM import_issues WHERE decision_code = 'D26'")).isEqualTo(2);   // S01 and S02
    }

    @Test
    void passwordsAreStoredHashedNeverInPlainText_D25() {
        runImport();

        assertThat(count("SELECT count(*) FROM users WHERE password_hash LIKE '$2%'")).isEqualTo(12);
        assertThat(count("SELECT count(*) FROM users WHERE password_hash = '" + DEMO_PASSWORD + "'")).isZero();
    }

    @Test
    void runningTheImportAgainAddsNothing_D28() {
        runImport();
        Map<String, FileResult> second = runImport();

        assertThat(second.get("stores.csv")).isEqualTo(new FileResult("stores.csv", 0, 5, 0));
        assertThat(second.get("users.csv")).isEqualTo(new FileResult("users.csv", 0, 12, 0));
        assertThat(count("SELECT count(*) FROM users")).isEqualTo(12);
    }

    @Test
    void reRunNeverOverwritesChangesMadeInTheApp_D28() {
        runImport();
        db.sql("UPDATE users SET name = 'Changed in the app' WHERE id = 'U03'").then().block(Duration.ofSeconds(10));

        runImport();

        assertThat(text("SELECT name FROM users WHERE id = 'U03'")).isEqualTo("Changed in the app");
    }

    /** PIP objective 1 with the real data: each role signs in with the demo password; the inactive user can't. */
    @Test
    void importedUsersCanSignInExceptTheInactiveOne() {
        runImport();
        WebTestClient client = WebTestClient.bindToApplicationContext(context).build();

        for (String email : List.of("rohit.menon@shelfwise.example", "arjun.rao@shelfwise.example", "meera.joshi@shelfwise.example")) {
            client.post().uri("/api/auth/login")
                    .bodyValue(Map.of("email", email, "password", DEMO_PASSWORD))
                    .exchange()
                    .expectStatus().isOk();
        }
        client.post().uri("/api/auth/login")
                .bodyValue(Map.of("email", "pooja.bansal@shelfwise.example", "password", DEMO_PASSWORD))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // --- helpers ---------------------------------------------------------

    private Map<String, FileResult> runImport() {
        List<FileResult> results = importService.runInsertOnly().block(Duration.ofMinutes(2));
        assertThat(results).isNotNull();
        return Map.of(results.get(0).file(), results.get(0), results.get(1).file(), results.get(1));
    }

    private long count(String sql) {
        return db.sql(sql).map((row, meta) -> row.get(0, Long.class)).one().block(Duration.ofSeconds(10));
    }

    private String text(String sql) {
        return db.sql(sql).map((row, meta) -> row.get(0, String.class)).one().block(Duration.ofSeconds(10));
    }
}
