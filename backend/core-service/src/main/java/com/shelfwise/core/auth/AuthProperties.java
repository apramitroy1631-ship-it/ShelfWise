package com.shelfwise.core.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Sign-in settings from application.yml (prefix {@code shelfwise.auth}). */
@ConfigurationProperties("shelfwise.auth")
public record AuthProperties(String jwtSecret, Duration accessTokenTtl, Duration refreshTokenTtl) {

    public AuthProperties {
        // HMAC-SHA256 needs a key of at least 256 bits; fail at start-up rather than issue weak tokens.
        if (jwtSecret == null || jwtSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("shelfwise.auth.jwt-secret (JWT_SECRET) must be at least 32 bytes");
        }
    }
}
