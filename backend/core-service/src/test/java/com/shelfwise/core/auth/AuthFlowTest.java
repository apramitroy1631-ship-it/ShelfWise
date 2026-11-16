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
        run("TRUNCATE refresh_tokens, login_attempts, users, stores CASCADE");
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

    /** Review issue 7: 5 wrong passwords lock that email for a few minutes, even for the right password. */
    @Test
    void fiveWrongPasswordsLockTheEmailForAFewMinutes() {
        for (int i = 0; i < 5; i++) {
            expectLoginRejected("arjun@test.example", "wrong-" + i);
        }
        client.post().uri("/api/auth/login").bodyValue(credentials("arjun@test.example", PASSWORD))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().exists("Retry-After")
                .expectBody().jsonPath("$.detail").isEqualTo("Too many sign-in attempts. Please try again in 5 minutes.");

        assertThat(login("meera@test.example", PASSWORD).user().id()).isEqualTo("U10");      // other people are unaffected

        run("UPDATE login_attempts SET locked_until = now() - interval '1 second'");          // the wait is over
        assertThat(login("arjun@test.example", PASSWORD).user().id()).isEqualTo("U03");
    }

    @Test
    void unknownEmailsAreLimitedTheSameWaySoTheLimitRevealsNothing() {
        for (int i = 0; i < 5; i++) {
            expectLoginRejected("nobody@test.example", "wrong-" + i);
        }
        client.post().uri("/api/auth/login").bodyValue(credentials("nobody@test.example", "wrong"))
                .exchange().expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void aSuccessfulSignInClearsTheCount() {
        for (int i = 0; i < 4; i++) {
            expectLoginRejected("arjun@test.example", "wrong-" + i);
        }
        login("arjun@test.example", PASSWORD);
        for (int i = 0; i < 4; i++) {
            expectLoginRejected("arjun@test.example", "wrong-" + i);           // 4 more: still not locked
        }
        login("arjun@test.example", PASSWORD);
    }

    @Test
    void manyWrongPasswordsFromOneAddressLockThatAddress() {
        for (int i = 0; i < LoginAttempts.MAX_PER_IP; i++) {
            expectLoginRejected("user" + i + "@test.example", "wrong");        // a different email each time
        }
        client.post().uri("/api/auth/login").bodyValue(credentials("meera@test.example", PASSWORD))
                .exchange().expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
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

        pastTheGraceWindow();
        expectRefreshRejected(first.refreshToken());
    }

    /** Reusing a swapped refresh token after the grace window looks like theft: every session of that user ends. */
    @Test
    void reusingAnOldRefreshTokenEndsAllSessions() {
        TokenResponse first = login("meera@test.example", PASSWORD);
        TokenResponse second = refresh(first.refreshToken());

        pastTheGraceWindow();
        expectRefreshRejected(first.refreshToken());    // reuse detected
        expectRefreshRejected(second.refreshToken());   // the legitimate newer session is ended too
    }

    /**
     * Review issue 1: the access token expires and 5 requests refresh at the same moment with the same token.
     * All 5 must get a new pair, and the user's other device must stay signed in.
     */
    @Test
    void manyRefreshesAtTheSameMomentAllSucceedAndSignNobodyOut() {
        TokenResponse laptop = login("arjun@test.example", PASSWORD);
        TokenResponse phone = login("arjun@test.example", PASSWORD);

        java.util.List<Integer> statuses = reactor.core.publisher.Flux.range(0, 5)
                .flatMap(i -> client.post().uri("/api/auth/refresh").bodyValue(new AuthDtos.RefreshRequest(laptop.refreshToken()))
                        .exchange().returnResult(TokenResponse.class).getResponseBody()
                        .map(body -> 200).defaultIfEmpty(0)
                        .onErrorReturn(-1)
                        .subscribeOn(reactor.core.scheduler.Schedulers.parallel()), 5)
                .collectList().block(Duration.ofSeconds(30));
        assertThat(statuses).hasSize(5).containsOnly(200);

        assertThat(refresh(phone.refreshToken()).user().id()).isEqualTo("U03");   // the other device is still signed in
    }

    @Test
    void aSignedOutTokenIsRefusedWithoutSigningOutOtherDevices() {
        TokenResponse laptop = login("arjun@test.example", PASSWORD);
        TokenResponse phone = login("arjun@test.example", PASSWORD);

        logout(laptop.refreshToken());
        expectRefreshRejected(laptop.refreshToken());   // e.g. a second browser tab still holding it
        refresh(phone.refreshToken());                  // the phone is unaffected
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

    /** As if the grace window for concurrent refreshes has passed since every swap so far. */
    private void pastTheGraceWindow() {
        run("UPDATE refresh_tokens SET rotated_at = rotated_at - interval '1 minute', revoked_at = revoked_at - interval '1 minute' WHERE rotated_at IS NOT NULL");
    }

    private void run(String sql) {
        db.sql(sql).then().block(Duration.ofSeconds(10));
    }
}
