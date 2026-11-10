package com.shelfwise.core.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.shelfwise.core.TestDatabaseConfig;
import com.shelfwise.core.auth.AuthDtos.TokenResponse;

/** Sign-in end to end: HTTP request -> security -> database, for every role and the main failure cases. */
@SpringBootTest
@Import(TestDatabaseConfig.class)
class AuthFlowTest {

    private static final String PASSWORD = "Test-Only-Pass-1";

    @Autowired
    private ApplicationContext context;
    @Autowired
    private DatabaseClient db;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToApplicationContext(context).build();
        String hash = passwordEncoder.encode(PASSWORD);
        run("TRUNCATE refresh_tokens, users, stores CASCADE");
        run("INSERT INTO stores (id, name, city, state, opened_on) VALUES ('S01','Whitefield','Bengaluru','Karnataka','2019-03-15')");
        db.sql("""
                INSERT INTO users (id, name, email, role, store_id, active, password_hash) VALUES
                  ('U01','Rohit Menon','rohit@test.example','ADMIN',NULL,true,:h),
                  ('U03','Arjun Rao','arjun@test.example','STORE_MANAGER','S01',true,:h),
                  ('U10','Meera Joshi','meera@test.example','FINANCE_APPROVER',NULL,true,:h),
                  ('U12','Pooja Bansal','pooja@test.example','FINANCE_APPROVER',NULL,false,:h)
                """).bind("h", hash).then().block(Duration.ofSeconds(10));
    }

    /** PIP objective 1: sign-in works for all roles. */
    @ParameterizedTest
    @CsvSource({
            "rohit@test.example, U01, ADMIN,            ",
            "arjun@test.example, U03, STORE_MANAGER,    S01",
            "meera@test.example, U10, FINANCE_APPROVER, "
    })
    void everyRoleCanSignInAndSeeThemselves(String email, String id, String role, String storeId) {
        TokenResponse tokens = login(email, PASSWORD);

        assertThat(tokens.user().id()).isEqualTo(id);
        assertThat(tokens.user().role()).isEqualTo(role);
        assertThat(tokens.user().storeId()).isEqualTo(storeId);
        assertThat(tokens.tokenType()).isEqualTo("Bearer");
        assertThat(tokens.expiresInSeconds()).isEqualTo(900);

        client.get().uri("/api/me").headers(h -> h.setBearerAuth(tokens.accessToken()))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(id)
                .jsonPath("$.role").isEqualTo(role);
    }

    @Test
    void emailIsCaseInsensitive() {
        assertThat(login("ARJUN@Test.Example", PASSWORD).user().id()).isEqualTo("U03");
    }

    @Test
    void wrongPasswordAndUnknownEmailGetTheSameAnswer() {
        expectLoginRejected("arjun@test.example", "wrong-password");
        expectLoginRejected("nobody@test.example", PASSWORD);
    }

    @Test
    void inactiveUserCannotSignIn_D20() {
        expectLoginRejected("pooja@test.example", PASSWORD);
    }

    @Test
    void requestsWithoutAValidTokenAreRejected() {
        client.get().uri("/api/me").exchange().expectStatus().isUnauthorized();

        String token = login("arjun@test.example", PASSWORD).accessToken();
        String tampered = token.substring(0, token.length() - 4) + "AAAA";
        client.get().uri("/api/me").headers(h -> h.setBearerAuth(tampered))
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void responsesNeverContainThePasswordHash() {
        client.post().uri("/api/auth/login").bodyValue(credentials("arjun@test.example", PASSWORD))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body).doesNotContain("$2a$").doesNotContain("passwordHash"));
    }

    @Test
    void deactivatedUserIsCutOffOnTheirNextRequest_D20() {
        TokenResponse tokens = login("arjun@test.example", PASSWORD);
        run("UPDATE users SET active = false WHERE id = 'U03'");

        client.get().uri("/api/me").headers(h -> h.setBearerAuth(tokens.accessToken()))
                .exchange().expectStatus().isUnauthorized();
        expectRefreshRejected(tokens.refreshToken());
    }

    @Test
    void refreshIssuesANewPairAndTheOldRefreshTokenStopsWorking() {
        TokenResponse first = login("meera@test.example", PASSWORD);
        TokenResponse second = refresh(first.refreshToken());

        assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
        client.get().uri("/api/me").headers(h -> h.setBearerAuth(second.accessToken()))
                .exchange().expectStatus().isOk();

        expectRefreshRejected(first.refreshToken());
    }

    /** Reusing a revoked refresh token looks like theft: every session of that user ends. */
    @Test
    void reusingAnOldRefreshTokenEndsAllSessions() {
        TokenResponse first = login("meera@test.example", PASSWORD);
        TokenResponse second = refresh(first.refreshToken());

        expectRefreshRejected(first.refreshToken());    // reuse detected
        expectRefreshRejected(second.refreshToken());   // the legitimate newer session is ended too
    }

    @Test
    void logoutRevokesTheRefreshTokenAndIsSafeToRepeat() {
        TokenResponse tokens = login("rohit@test.example", PASSWORD);

        logout(tokens.refreshToken());
        logout(tokens.refreshToken());
        expectRefreshRejected(tokens.refreshToken());
    }

    @Test
    void invalidRequestBodiesAreRejected() {
        client.post().uri("/api/auth/login").bodyValue(credentials("not-an-email", ""))
                .exchange().expectStatus().isBadRequest();
    }

    // --- helpers ---------------------------------------------------------

    private TokenResponse login(String email, String password) {
        return client.post().uri("/api/auth/login").bodyValue(credentials(email, password))
                .exchange()
                .expectStatus().isOk()
                .expectBody(TokenResponse.class)
                .returnResult().getResponseBody();
    }

    private void expectLoginRejected(String email, String password) {
        client.post().uri("/api/auth/login").bodyValue(credentials(email, password))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED)
                .expectBody()
                .jsonPath("$.detail").isEqualTo(AuthService.INVALID_CREDENTIALS);
    }

    private TokenResponse refresh(String refreshToken) {
        return client.post().uri("/api/auth/refresh").bodyValue(new AuthDtos.RefreshRequest(refreshToken))
                .exchange()
                .expectStatus().isOk()
                .expectBody(TokenResponse.class)
                .returnResult().getResponseBody();
    }

    private void expectRefreshRejected(String refreshToken) {
        client.post().uri("/api/auth/refresh").bodyValue(new AuthDtos.RefreshRequest(refreshToken))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    private void logout(String refreshToken) {
        client.post().uri("/api/auth/logout").bodyValue(new AuthDtos.RefreshRequest(refreshToken))
                .exchange()
                .expectStatus().isNoContent();
    }

    private static AuthDtos.LoginRequest credentials(String email, String password) {
        return new AuthDtos.LoginRequest(email, password);
    }

    private void run(String sql) {
        db.sql(sql).then().block(Duration.ofSeconds(10));
    }
}
