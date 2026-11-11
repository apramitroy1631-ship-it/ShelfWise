package com.shelfwise.notification;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import javax.crypto.spec.SecretKeySpec;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

/** Access tokens shaped like the core service's (same issuer and claims), signed with the local test key. */
final class TestTokens {

    static final String LOCAL_KEY = "local-development-only-secret-do-not-use-elsewhere";

    private TestTokens() {
    }

    static String token(String userId, String role, String storeId) {
        return sign(LOCAL_KEY, userId, role, storeId);
    }

    static String sign(String key, String userId, String role, String storeId) {
        var secret = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer("shelfwise-core").subject(userId).issuedAt(now).expiresAt(now.plus(15, ChronoUnit.MINUTES))
                .claim("role", role);
        if (storeId != null) {
            claims.claim("storeId", storeId);
        }
        return new NimbusJwtEncoder(new ImmutableSecret<>(secret))
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()))
                .getTokenValue();
    }
}
