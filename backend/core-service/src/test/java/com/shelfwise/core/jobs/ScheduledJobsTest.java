package com.shelfwise.core.jobs;

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
import com.shelfwise.core.jobs.ScheduledJobs.Job;
import com.shelfwise.core.jobs.ScheduledJobs.Run;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The nightly jobs (M23): expiring stale drafts (D22) and the low-stock recount, one instance at a time. */
@SpringBootTest
@Import(TestDatabaseConfig.class)
class ScheduledJobsTest {

    private static final String PASSWORD = "Test-Only-Pass-1";

    @Autowired
    private ScheduledJobs jobs;
    @Autowired
    private DatabaseClient db;
    @Autowired
    private ApplicationContext context;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private ConnectionFactory connectionFactory;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToApplicationContext(context).build();
        run("""
            TRUNCATE job_runs, outbox_events, audit_log, po_lines, purchase_orders, stock_movements, inventory, products,
                     suppliers, refresh_tokens, users, stores CASCADE
            """);
        // TRUNCATE users CASCADE also empties app_settings (it references users): put the expiry rule back
        run("INSERT INTO app_settings (key, value, description) VALUES ('purchaseOrders.draftExpiryDays', '14', 'test') ON CONFLICT (key) DO UPDATE SET value = '14'");
        run("INSERT INTO stores (id, name, city, state, opened_on) VALUES ('S01','Whitefield','Bengaluru','Karnataka','2019-03-15'), ('S02','Madhapur','Hyderabad','Telangana','2020-04-15')");
        db.sql("""
                INSERT INTO users (id, name, email, role, store_id, active, password_hash) VALUES
                  ('U01','Rohit Menon','rohit@test.example','ADMIN',NULL,true,:h),
                  ('U03','Arjun Rao','arjun@test.example','STORE_MANAGER','S01',true,:h)
                """).bind("h", passwordEncoder.encode(PASSWORD)).then().block(Duration.ofSeconds(10));
        run("INSERT INTO suppliers (id, name) VALUES ('SUP23','Supplier SUP23')");
        run("""
            INSERT INTO products (sku, name, category, price_inr, cost_price_inr, gst_rate, supplier_id, status, reorder_level, catalogued_on, source) VALUES
              ('SW-SNK-00091','Smart Buy Potato Chips 150g','Snacks',25,20,12,'SUP23','ACTIVE',25,'2025-12-02','IMPORT'),
              ('SW-GRO-00055','Home Basics Iodised Salt 1kg','Grocery',31,25,0,'SUP23','ACTIVE',10,'2025-01-01','IMPORT'),
              ('SW-SNK-00064','Fresh Basket Chocolate Bar 100g','Snacks',52,42,12,'SUP23','ARCHIVED',25,'2025-04-20','IMPORT')
            """);
        run("""
            INSERT INTO inventory (store_id, sku, quantity, reorder_level) VALUES
              ('S01','SW-SNK-00091',37,25), ('S01','SW-GRO-00055',0,10), ('S01','SW-SNK-00064',0,25), ('S02','SW-SNK-00091',5,25)
            """);
    }

    // --- expire drafts (D22) ------------------------------------------------------------

    @Test
    void draftsUntouchedForMoreThan14DaysExpire_D22() {
        draft("PO-0011", "IMPORT", "now() - interval '250 days'", "now()");           // imported: counts from creation
        draft("PO-0012", "IMPORT", "now() - interval '3 days'", "now()");
        draft("PO-10001", "APP", "now() - interval '40 days'", "now() - interval '20 days'");   // app: counts from last change
        draft("PO-10002", "APP", "now() - interval '40 days'", "now() - interval '2 days'");    // changed recently: stays
        run("UPDATE purchase_orders SET status = 'SUBMITTED' WHERE id = 'PO-0012'");
        draft("PO-0013", "IMPORT", "now() - interval '100 days'", "now()");
        run("UPDATE purchase_orders SET status = 'SUBMITTED' WHERE id = 'PO-0013'");  // not a draft: untouched

        Run result = jobs.run(Job.EXPIRE_DRAFTS, "MANUAL", "U01").block(Duration.ofSeconds(30));

        assertThat(result.outcome()).isEqualTo("DONE");
        assertThat(result.affected()).isEqualTo(2);
        assertThat(status("PO-0011")).isEqualTo("EXPIRED");
        assertThat(status("PO-10001")).isEqualTo("EXPIRED");
        assertThat(status("PO-10002")).isEqualTo("DRAFT");
        assertThat(status("PO-0013")).isEqualTo("SUBMITTED");
        assertThat(number("SELECT count(*) FROM audit_log WHERE action = 'PO_EXPIRED' AND actor_user_id IS NULL")).isEqualTo(2);
        assertThat(number("SELECT version FROM purchase_orders WHERE id = 'PO-0011'")).isEqualTo(1);   // stale screens get 409
    }

    @Test
    void aSecondRunFindsNothingMore() {
        draft("PO-0011", "IMPORT", "now() - interval '250 days'", "now()");
        jobs.run(Job.EXPIRE_DRAFTS, "SCHEDULE", null).block(Duration.ofSeconds(30));

        Run again = jobs.run(Job.EXPIRE_DRAFTS, "SCHEDULE", null).block(Duration.ofSeconds(30));

        assertThat(again.affected()).isZero();
        assertThat(number("SELECT count(*) FROM job_runs WHERE job = 'EXPIRE_DRAFTS'")).isEqualTo(2);   // every run is recorded
    }

    /** Remote config (DL-06): the expiry limit changes without a redeploy. */
    @Test
    void theExpiryLimitComesFromRemoteConfig() {
        draft("PO-0011", "IMPORT", "now() - interval '10 days'", "now()");
        run("UPDATE app_settings SET value = '7' WHERE key = 'purchaseOrders.draftExpiryDays'");

        assertThat(jobs.run(Job.EXPIRE_DRAFTS, "MANUAL", "U01").block(Duration.ofSeconds(30)).affected()).isEqualTo(1);
    }

    // --- low-stock recount ----------------------------------------------------------------

    @Test
    void theRecountSendsOneMarkerPerStoreAndKeepsTheCounts() {
        Run result = jobs.run(Job.LOW_STOCK_RECOUNT, "SCHEDULE", null).block(Duration.ofSeconds(30));

        assertThat(result.outcome()).isEqualTo("DONE");
        assertThat(result.affected()).isEqualTo(2);
        assertThat(number("SELECT count(*) FROM outbox_events WHERE event_type = 'StockHealthChanged'")).isEqualTo(2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stores = (List<Map<String, Object>>) result.details().get("stores");
        assertThat(stores.get(0)).containsEntry("store", "S01").containsEntry("outOfStock", 1L).containsEntry("runningLow", 0L);  // archived bar left out
        assertThat(stores.get(1)).containsEntry("store", "S02").containsEntry("runningLow", 1L);
    }

    // --- one instance at a time -------------------------------------------------------------

    /** Two copies of the core service wake up at the same moment: one does the work, the other records SKIPPED. */
    @Test
    void twoInstancesAtTheSameMomentRunTheJobOnce() {
        draft("PO-0011", "IMPORT", "now() - interval '250 days'", "now()");
        // another instance mid-run: its own database connection holds the job's lock (kept out of the pool meanwhile)
        Connection otherInstance = Mono.from(connectionFactory.create()).block(Duration.ofSeconds(10));
        Flux.from(otherInstance.createStatement("SELECT pg_advisory_lock(23001)").execute()).flatMap(r -> r.getRowsUpdated()).blockLast(Duration.ofSeconds(10));
        try {
            List<Run> runs = Flux.range(0, 2).flatMap(i -> jobs.run(Job.EXPIRE_DRAFTS, "SCHEDULE", null), 2)
                    .collectList().block(Duration.ofSeconds(30));
            assertThat(runs).extracting(Run::outcome).containsOnly("SKIPPED");
            assertThat(status("PO-0011")).isEqualTo("DRAFT");
        } finally {
            Flux.from(otherInstance.createStatement("SELECT pg_advisory_unlock(23001)").execute()).flatMap(r -> r.getRowsUpdated()).blockLast(Duration.ofSeconds(10));
            Mono.from(otherInstance.close()).block(Duration.ofSeconds(10));
        }
        List<Run> runs = Flux.range(0, 2).flatMap(i -> jobs.run(Job.EXPIRE_DRAFTS, "SCHEDULE", null), 2)
                .collectList().block(Duration.ofSeconds(30));
        assertThat(runs.stream().mapToInt(Run::affected).sum()).isEqualTo(1);           // expired once, never twice
        assertThat(number("SELECT count(*) FROM audit_log WHERE action = 'PO_EXPIRED'")).isEqualTo(1);
    }

    // --- admin API -------------------------------------------------------------------------

    @Test
    void onlyAdminsRunJobsAndSeeRuns() {
        String admin = login("rohit@test.example");
        String manager = login("arjun@test.example");
        draft("PO-0011", "IMPORT", "now() - interval '250 days'", "now()");

        client.post().uri("/api/admin/jobs/EXPIRE_DRAFTS/run").headers(h -> h.setBearerAuth(manager)).exchange().expectStatus().isForbidden();
        client.post().uri("/api/admin/jobs/NOPE/run").headers(h -> h.setBearerAuth(admin)).exchange().expectStatus().isNotFound();
        client.post().uri("/api/admin/jobs/EXPIRE_DRAFTS/run").headers(h -> h.setBearerAuth(admin)).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.affected").isEqualTo(1).jsonPath("$.trigger").isEqualTo("MANUAL");
        client.get().uri("/api/admin/jobs/runs").headers(h -> h.setBearerAuth(admin)).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$[0].job").isEqualTo("EXPIRE_DRAFTS")
                .jsonPath("$[0].details.expired[0]").isEqualTo("PO-0011");
        assertThat(number("SELECT count(*) FROM job_runs WHERE started_by = 'U01'")).isEqualTo(1);
    }

    // --- helpers ---------------------------------------------------------------------------

    private void draft(String id, String source, String createdAt, String updatedAt) {
        run("INSERT INTO purchase_orders (id, store_id, supplier_id, created_by, status, total_amount, created_at, updated_at, source) "
                + "VALUES ('" + id + "','S01','SUP23','U03','DRAFT',100," + createdAt + "," + updatedAt + ",'" + source + "')");
    }

    private String status(String id) {
        return db.sql("SELECT status FROM purchase_orders WHERE id = :id").bind("id", id)
                .map((row, meta) -> row.get(0, String.class)).one().block(Duration.ofSeconds(10));
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
