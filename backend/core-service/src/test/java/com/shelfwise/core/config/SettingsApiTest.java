package com.shelfwise.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.HashMap;
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

/** Remote configuration (DL-06, M27): public /config, admin changes with checks, versions and audit, live effect. */
@SpringBootTest
@Import(TestDatabaseConfig.class)
class SettingsApiTest {

    private static final String PASSWORD = "Test-Only-Pass-1";

    @Autowired
    private ApplicationContext context;
    @Autowired
    private DatabaseClient db;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private AppSettings appSettings;

    private WebTestClient client;
    private String admin;
    private String manager;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToApplicationContext(context).build();
        run("TRUNCATE audit_log, refresh_tokens, users, stores CASCADE");   // also empties app_settings (it references users)
        run("""
            INSERT INTO app_settings (key, value, description) VALUES
              ('purchaseOrders.secondApprovalAboveInr', '50000', 'POs above this total need two different finance approvers'),
              ('ui.maintenanceBanner', 'null', 'Text shown at the top of web and mobile; null hides it'),
              ('features.adminReviewQueue', 'true', 'Admin review screen (D10)'),
              ('orders.stuckAfterDays', '7', 'Open orders older than this are flagged stuck (D32)')
            ON CONFLICT (key) DO NOTHING
            """);
        run("INSERT INTO stores (id, name, city, state, opened_on) VALUES ('S01','Whitefield','Bengaluru','Karnataka','2019-03-15')");
        db.sql("""
                INSERT INTO users (id, name, email, role, store_id, active, password_hash) VALUES
                  ('U01','Rohit Menon','rohit@test.example','ADMIN',NULL,true,:h),
                  ('U03','Arjun Rao','arjun@test.example','STORE_MANAGER','S01',true,:h)
                """).bind("h", passwordEncoder.encode(PASSWORD)).then().block(Duration.ofSeconds(10));
        admin = login("rohit@test.example");
        manager = login("arjun@test.example");
    }

    @Test
    void theAppsReadPublicSettingsWithoutSigningIn() {
        client.get().uri("/api/config").exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.values['ui.maintenanceBanner']").doesNotExist()      // null: no banner
                .jsonPath("$.values['features.adminReviewQueue']").isEqualTo(true)
                .jsonPath("$.values['purchaseOrders.secondApprovalAboveInr']").doesNotExist();   // internal limits stay private
    }

    /** The brief's test of remote config: an admin turns the banner on, and the next /config shows it. No redeploy. */
    @Test
    void anAdminTurnsTheMaintenanceBannerOnAndOff() {
        change(admin, "ui.maintenanceBanner", "  Stock count tonight 9-11 pm  ", 200);

        client.get().uri("/api/config").exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.values['ui.maintenanceBanner']").isEqualTo("Stock count tonight 9-11 pm");   // trimmed
        assertThat(count("SELECT count(*) FROM audit_log WHERE action = 'SETTING_CHANGED' AND entity_id = 'ui.maintenanceBanner' AND actor_user_id = 'U01'")).isEqualTo(1);

        change(admin, "ui.maintenanceBanner", null, 200);                    // null hides it again
        client.get().uri("/api/config").exchange().expectBody().jsonPath("$.values['ui.maintenanceBanner']").doesNotExist();
    }

    /** A change is used by the very next request on the server: e.g. the second-approval limit. */
    @Test
    void aChangedLimitIsUsedRightAway() {
        change(admin, "purchaseOrders.secondApprovalAboveInr", 75000, 200);

        assertThat(appSettings.integer("purchaseOrders.secondApprovalAboveInr", 50_000).block(Duration.ofSeconds(10))).isEqualTo(75_000);
    }

    @Test
    void valuesMustFitTheSettingsRule() {
        change(admin, "purchaseOrders.secondApprovalAboveInr", -5, 400);
        change(admin, "purchaseOrders.secondApprovalAboveInr", "lots", 400);
        change(admin, "orders.stuckAfterDays", 0, 400);
        change(admin, "features.adminReviewQueue", "yes", 400);
        change(admin, "ui.maintenanceBanner", "x".repeat(201), 400);
        change(admin, "ui.maintenanceBanner", 42, 400);
        change(admin, "no.such.setting", 1, 404);
        assertThat(count("SELECT count(*) FROM audit_log")).isZero();         // nothing was saved
    }

    /** Two admins edit the same setting from stale screens: the second gets 409 instead of silently overwriting. */
    @Test
    void aStaleVersionIsRefused() {
        long version = version("orders.stuckAfterDays");
        put(admin, "orders.stuckAfterDays", 10, version, 200);

        put(admin, "orders.stuckAfterDays", 3, version, 409);
        assertThat(count("SELECT (value)::int FROM app_settings WHERE key = 'orders.stuckAfterDays'")).isEqualTo(10);
    }

    @Test
    void onlyAdminsSeeAndChangeAllSettings() {
        client.get().uri("/api/admin/settings").headers(h -> h.setBearerAuth(manager)).exchange().expectStatus().isForbidden();
        put(manager, "ui.maintenanceBanner", "hi", 0, 403);
        client.get().uri("/api/admin/settings").exchange().expectStatus().isUnauthorized();

        List<?> all = client.get().uri("/api/admin/settings").headers(h -> h.setBearerAuth(admin)).exchange()
                .expectStatus().isOk().expectBodyList(Map.class).returnResult().getResponseBody();
        assertThat(all).hasSizeGreaterThanOrEqualTo(4);
        assertThat(((Map<?, ?>) all.get(0)).get("rule")).isNotNull();
    }

    // --- helpers ---------------------------------------------------------------------------

    private void change(String token, String key, Object value, int status) {
        put(token, key, value, version(key), status);
    }

    private void put(String token, String key, Object value, long version, int status) {
        Map<String, Object> body = new HashMap<>();
        body.put("value", value);
        body.put("version", version);
        client.put().uri("/api/admin/settings/" + key).headers(h -> h.setBearerAuth(token)).bodyValue(body)
                .exchange().expectStatus().isEqualTo(status);
    }

    private long version(String key) {
        return db.sql("SELECT coalesce((SELECT version FROM app_settings WHERE key = :k), 0)").bind("k", key)
                .map((row, meta) -> ((Number) row.get(0)).longValue()).one().block(Duration.ofSeconds(10));
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
