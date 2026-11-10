package com.shelfwise.core.auth;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    /** Checked when the email is unknown, so a failed sign-in takes the same time either way. */
    private final String dummyHash;

    public AuthService(UserAccountRepository users, RefreshTokenStore refreshTokens, TokenService tokens,
                       PasswordEncoder passwordEncoder, AuthProperties properties) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.tokens = tokens;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.dummyHash = passwordEncoder.encode("dummy-password-for-timing");
    }

    public Mono<TokenResponse> login(String email, String password) {
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
     * Presenting an already-revoked token means it was copied or stolen, so all of that user's sessions end.
     */
    public Mono<TokenResponse> refresh(String refreshToken) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return refreshTokens.findByHash(TokenService.hash(refreshToken))
                .switchIfEmpty(Mono.error(unauthorized(INVALID_SESSION)))
                .flatMap(stored -> {
                    if (stored.isRevoked()) {
                        log.warn("Revoked refresh token reused for user {}: ending all sessions", stored.userId());
                        return refreshTokens.revokeAllForUser(stored.userId())
                                .then(Mono.error(unauthorized(INVALID_SESSION)));
                    }
                    if (stored.isExpired(now)) {
                        return Mono.error(unauthorized(INVALID_SESSION));
                    }
                    return refreshTokens.revokeIfActive(stored.id()).flatMap(revoked -> revoked
                            ? users.findById(stored.userId())
                                    .filter(UserAccount::active)   // deactivated users can't refresh (D20)
                                    .switchIfEmpty(Mono.error(unauthorized(INVALID_SESSION)))
                                    .flatMap(this::issueTokens)
                            // another request used this token at the same moment: treat as reuse
                            : refreshTokens.revokeAllForUser(stored.userId())
                                    .then(Mono.error(unauthorized(INVALID_SESSION))));
                });
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

    private static ResponseStatusException unauthorized(String message) {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, message);
    }
}
