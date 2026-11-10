package com.shelfwise.core.auth;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;

import reactor.core.publisher.Mono;

/**
 * Refresh tokens are stored only as a SHA-256 hash, so a database leak does not leak usable tokens.
 * A token is single-use: refreshing revokes it and issues a new one (rotation).
 */
@Repository
public class RefreshTokenStore {

    public record StoredToken(UUID id, String userId, OffsetDateTime expiresAt, OffsetDateTime revokedAt) {

        boolean isRevoked() {
            return revokedAt != null;
        }

        boolean isExpired(OffsetDateTime now) {
            return !expiresAt.isAfter(now);
        }
    }

    private final DatabaseClient db;

    public RefreshTokenStore(DatabaseClient db) {
        this.db = db;
    }

    public Mono<Void> save(UUID id, String userId, String tokenHash, OffsetDateTime expiresAt) {
        return db.sql("INSERT INTO refresh_tokens (id, user_id, token_hash, expires_at) VALUES (:id, :userId, :hash, :expiresAt)")
                .bind("id", id)
                .bind("userId", userId)
                .bind("hash", tokenHash)
                .bind("expiresAt", expiresAt)
                .then();
    }

    public Mono<StoredToken> findByHash(String tokenHash) {
        return db.sql("SELECT id, user_id, expires_at, revoked_at FROM refresh_tokens WHERE token_hash = :hash")
                .bind("hash", tokenHash)
                .map((row, meta) -> new StoredToken(
                        row.get("id", UUID.class),
                        row.get("user_id", String.class),
                        row.get("expires_at", OffsetDateTime.class),
                        row.get("revoked_at", OffsetDateTime.class)))
                .one();
    }

    /**
     * Revokes the token only if it is still active, in one atomic statement.
     * Returns false if someone else revoked it first (two refreshes racing with the same token).
     */
    public Mono<Boolean> revokeIfActive(UUID id) {
        return db.sql("UPDATE refresh_tokens SET revoked_at = now() WHERE id = :id AND revoked_at IS NULL")
                .bind("id", id)
                .fetch().rowsUpdated()
                .map(updated -> updated == 1);
    }

    /** Ends every session of a user (used when a revoked token is reused: likely theft). */
    public Mono<Long> revokeAllForUser(String userId) {
        return db.sql("UPDATE refresh_tokens SET revoked_at = now() WHERE user_id = :userId AND revoked_at IS NULL")
                .bind("userId", userId)
                .fetch().rowsUpdated();
    }
}
