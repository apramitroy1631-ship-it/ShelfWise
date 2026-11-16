package com.shelfwise.core.auth;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.shelfwise.core.auth.AuthDtos.TokenResponse;
import com.shelfwise.core.auth.AuthDtos.UserSummary;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Sign-in, token refresh and sign-out. */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** One message for every sign-in failure, so callers can't probe which emails exist. */
    static final String INVALID_CREDENTIALS = "Invalid email or password";
    static final String INVALID_SESSION = "Session expired or invalid; please sign in again";

    private final UserAccountRepository users;
    private final RefreshTokenStore refreshTokens;
    private final TokenService tokens;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties properties;
    private final LoginAttempts attempts;
    /** Checked when the email is unknown, so a failed sign-in takes the same time either way. */
    private final String dummyHash;

    public AuthService(UserAccountRepository users, RefreshTokenStore refreshTokens, TokenService tokens,
                       PasswordEncoder passwordEncoder, AuthProperties properties, LoginAttempts attempts) {
        this.attempts = attempts;
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.tokens = tokens;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.dummyHash = passwordEncoder.encode("dummy-password-for-timing");
    }

    /** Signs in, unless this email or IP address has had too many wrong tries recently (review issue 7). */
    public Mono<TokenResponse> login(String email, String password, String ip) {
        String emailKey = LoginAttempts.emailKey(email);
        String ipKey = LoginAttempts.ipKey(ip);
        return attempts.lockedFor(List.of(emailKey, ipKey))
                .flatMap(wait -> Mono.<TokenResponse>error(tooManyAttempts(wait)))
                .switchIfEmpty(Mono.defer(() -> checkPassword(email, password)
                        .onErrorResume(e -> e instanceof ResponseStatusException r && r.getStatusCode() == HttpStatus.UNAUTHORIZED,
                                e -> attempts.recordFailure(emailKey, ipKey).then(Mono.error(e)))
                        .flatMap(tokens -> attempts.clear(emailKey).thenReturn(tokens))));
    }

    private Mono<TokenResponse> checkPassword(String email, String password) {
        return users.findByEmail(email.trim())
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(found -> {
                    String hash = found.map(UserAccount::passwordHash).orElse(dummyHash);
                    return passwordMatches(password, hash).flatMap(matches -> {
                        if (!matches || found.isEmpty()) {
                            log.info("Sign-in rejected: wrong email or password");
                            return Mono.error(unauthorized(INVALID_CREDENTIALS));
                        }
                        UserAccount user = found.get();
                        if (!user.active()) {
                            log.info("Sign-in rejected: user {} is inactive", user.id());   // D20
                            return Mono.error(unauthorized(INVALID_CREDENTIALS));
                        }
                        log.info("Signed in: user {} ({})", user.id(), user.role());
                        return issueTokens(user);
                    });
                });
    }

    /**
     * Swaps a refresh token for a new pair. The old token is revoked (rotation).
     * <ul>
     *   <li>Used again within the grace window after its swap: several requests refreshed at the same moment (review
     *       issue 1). Each gets its own new pair; nobody is signed out.</li>
     *   <li>Used again later: it was copied or stolen, so all of that user's sessions end.</li>
     *   <li>Revoked by sign-out (or by such a theft response): simply refused, without ending other sessions.</li>
     * </ul>
     */
    public Mono<TokenResponse> refresh(String refreshToken) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return refreshTokens.findByHash(TokenService.hash(refreshToken))
                .switchIfEmpty(Mono.error(unauthorized(INVALID_SESSION)))
                .flatMap(stored -> {
                    if (stored.isRevoked()) {
                        if (stored.rotatedAt() == null) {
                            return Mono.error(unauthorized(INVALID_SESSION));            // signed out
                        }
                        if (stored.rotatedAt().isAfter(now.minus(properties.refreshReuseGrace()))) {
                            return issueForActiveUser(stored.userId());                  // concurrent refresh
                        }
                        log.warn("Rotated refresh token reused for user {}: ending all sessions", stored.userId());
                        return refreshTokens.revokeAllForUser(stored.userId())
                                .then(Mono.error(unauthorized(INVALID_SESSION)));
                    }
                    if (stored.isExpired(now)) {
                        return Mono.error(unauthorized(INVALID_SESSION));
                    }
                    // whether we swapped it or another request did a moment ago, this caller had the valid token
                    return refreshTokens.rotateIfActive(stored.id()).then(issueForActiveUser(stored.userId()));
                });
    }

    private Mono<TokenResponse> issueForActiveUser(String userId) {
        return users.findById(userId)
                .filter(UserAccount::active)   // deactivated users can't refresh (D20)
                .switchIfEmpty(Mono.error(unauthorized(INVALID_SESSION)))
                .flatMap(this::issueTokens);
    }

    /** Always succeeds, so signing out twice (or with an unknown token) is harmless. */
    public Mono<Void> logout(String refreshToken) {
        return refreshTokens.findByHash(TokenService.hash(refreshToken))
                .flatMap(stored -> refreshTokens.revokeIfActive(stored.id()))
                .then();
    }

    private Mono<TokenResponse> issueTokens(UserAccount user) {
        String refreshToken = tokens.newRefreshToken();
        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plus(properties.refreshTokenTtl());
        return refreshTokens.save(UUID.randomUUID(), user.id(), TokenService.hash(refreshToken), expiresAt)
                .thenReturn(new TokenResponse(
                        tokens.createAccessToken(user),
                        "Bearer",
                        tokens.accessTokenTtlSeconds(),
                        refreshToken,
                        UserSummary.from(user)));
    }

    /** BCrypt is deliberately slow (CPU work), so it runs off the request threads to keep WebFlux non-blocking. */
    private Mono<Boolean> passwordMatches(String raw, String hash) {
        return Mono.fromCallable(() -> passwordEncoder.matches(raw, hash))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 429 with a Retry-After header and a message saying how long to wait. */
    static ResponseStatusException tooManyAttempts(Duration wait) {
        long minutes = Math.max(1, (wait.toSeconds() + 59) / 60);
        String message = "Too many sign-in attempts. Please try again in " + minutes + (minutes == 1 ? " minute." : " minutes.");
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(wait.toSeconds()));
        return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, message) {
            @Override
            public HttpHeaders getHeaders() {
                return headers;
            }
        };
    }

    private static ResponseStatusException unauthorized(String message) {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, message);
    }
}
