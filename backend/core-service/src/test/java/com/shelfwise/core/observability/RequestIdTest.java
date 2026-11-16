package com.shelfwise.core.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.shelfwise.core.TestDatabaseConfig;

/**
 * Logging and monitoring (M28): every request has an id that is returned, logged, and saved with the events it
 * causes (so the notification service logs it too); logs never carry query strings; the outbox has a health check.
 */
@SpringBootTest
@Import(TestDatabaseConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class RequestIdTest {

    private static final String PASSWORD = "Test-Only-Pass-1";

    @Autowired
    private DatabaseClient db;
    @Autowired
    private ApplicationContext context;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private OutboxHealthIndicator outboxHealth;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToApplicationContext(context).build();
        run("TRUNCATE outbox_events, order_lines, customer_orders, stock_movements, inventory, products, suppliers, refresh_tokens, users, stores CASCADE");
        run("INSERT INTO stores (id, name, city, state, opened_on) VALUES ('S01','Whitefield','Bengaluru','Karnataka','2019-03-15')");
        db.sql("INSERT INTO users (id, name, email, role, store_id, active, password_hash) VALUES ('U03','Arjun Rao','arjun@test.example','STORE_MANAGER','S01',true,:h)")
                .bind("h", passwordEncoder.encode(PASSWORD)).then().block(Duration.ofSeconds(10));
        run("""
            INSERT INTO customer_orders (id, store_id, placed_by, status, customer_name, customer_phone, total_amount, created_at, source)
            VALUES ('ORD-000010','S01','U03','PENDING','Tara Bose','+91 9810710625',100,now(),'IMPORT')
            """);
    }

    @Test
    void everyRequestGetsAnIdInTheResponseAndOnItsLogLine(CapturedOutput output) {
        String id = client.post().uri("/api/auth/login").bodyValue(Map.of("email", "arjun@test.example", "password", "wrong"))
                .exchange().expectStatus().isUnauthorized()
                .returnResult(Void.class).getResponseHeaders().getFirst(RequestIdFilter.HEADER);

        assertThat(id).matches("[0-9a-f-]{36}");
        assertThat(output.getOut()).containsPattern("core-service," + id + "].*POST /api/auth/login 401 \\d+ ms");
    }

    @Test
    void aCallersIdIsKeptOnlyIfItIsShortAndPlain() {
        assertThat(callWithId("gateway-1234-abcd")).isEqualTo("gateway-1234-abcd");
        assertThat(callWithId("short")).isNotEqualTo("short").matches("[0-9a-f-]{36}");
        assertThat(callWithId("has spaces; and=signs")).matches("[0-9a-f-]{36}");
        assertThat(callWithId("x".repeat(65))).matches("[0-9a-f-]{36}");
    }

    @Test
    void searchesAreLoggedWithoutTheirQueryString_D16(CapturedOutput output) {
        client.get().uri("/api/orders?q=Tara Bose").headers(h -> h.setBearerAuth(login())).exchange().expectStatus().isOk();

        assertThat(output.getOut()).contains("GET /api/orders 200").doesNotContain("Tara");
    }

    /** The id travels with the event: the relay sends it on, and the notification service logs it (EventListenerTest). */
    @Test
    void anEventRemembersTheRequestThatCausedIt() {
        String token = login();
        long version = count("SELECT version FROM customer_orders WHERE id = 'ORD-000010'");
        String id = client.post().uri("/api/orders/ORD-000010/status").headers(h -> h.setBearerAuth(token))
                .bodyValue(Map.of("to", "PACKED", "version", version))
                .exchange().expectStatus().isOk()
                .returnResult(Void.class).getResponseHeaders().getFirst(RequestIdFilter.HEADER);

        assertThat(count("SELECT count(*) FROM outbox_events WHERE event_type = 'StuckWorkChanged' AND request_id = '" + id + "'")).isEqualTo(1);
    }

    @Test
    void theOutboxHealthCheckGoesDownWhenEventsStopLeaving() {
        assertThat(outboxHealth.health().block(Duration.ofSeconds(10)).getStatus()).isEqualTo(Status.UP);

        run("""
            INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, created_at)
            VALUES (gen_random_uuid(), 'STORE', 'S01', 'StuckWorkChanged', '{}', now() - interval '10 minutes')
            """);

        var health = outboxHealth.health().block(Duration.ofSeconds(10));
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("waiting", 1L);
        // the platform's readiness check ignores it: the core keeps selling while RabbitMQ is down (DL-03)
        client.get().uri("/actuator/health/readiness").exchange().expectStatus().isOk();
        client.get().uri("/actuator/health").exchange().expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.components.outbox.status").isEqualTo("DOWN");
    }

    // --- helpers -------------------------------------------------------------------------

    private String callWithId(String id) {
        return client.get().uri("/actuator/health/liveness").header(RequestIdFilter.HEADER, id).exchange()
                .returnResult(Void.class).getResponseHeaders().getFirst(RequestIdFilter.HEADER);
    }

    private String login() {
        return client.post().uri("/api/auth/login").bodyValue(Map.of("email", "arjun@test.example", "password", PASSWORD))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody().get("accessToken").toString();
    }

    private long count(String sql) {
        return db.sql(sql).map((row, meta) -> ((Number) row.get(0)).longValue()).one().block(Duration.ofSeconds(10));
    }

    private void run(String sql) {
        db.sql(sql).then().block(Duration.ofSeconds(30));
    }
}
