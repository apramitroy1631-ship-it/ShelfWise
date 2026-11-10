package com.shelfwise.core.auth;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;

import io.r2dbc.spi.Row;
import reactor.core.publisher.Mono;

/** Reads users from the database (non-blocking). */
@Repository
public class UserAccountRepository {

    private static final String COLUMNS = "id, name, email, role, store_id, active, password_hash";

    private final DatabaseClient db;

    public UserAccountRepository(DatabaseClient db) {
        this.db = db;
    }

    /** Email match ignores case, matching the unique index on lower(email). */
    public Mono<UserAccount> findByEmail(String email) {
        return db.sql("SELECT " + COLUMNS + " FROM users WHERE lower(email) = lower(:email)")
                .bind("email", email)
                .map((row, meta) -> toUser(row))
                .one();
    }

    public Mono<UserAccount> findById(String id) {
        return db.sql("SELECT " + COLUMNS + " FROM users WHERE id = :id")
                .bind("id", id)
                .map((row, meta) -> toUser(row))
                .one();
    }

    private static UserAccount toUser(Row row) {
        return new UserAccount(
                row.get("id", String.class),
                row.get("name", String.class),
                row.get("email", String.class),
                UserAccount.Role.valueOf(row.get("role", String.class)),
                row.get("store_id", String.class),
                Boolean.TRUE.equals(row.get("active", Boolean.class)),
                row.get("password_hash", String.class));
    }
}
