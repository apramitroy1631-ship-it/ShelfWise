package com.shelfwise.core.config;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;

import reactor.core.publisher.Mono;

/**
 * Reads remote configuration (DL-06) from the app_settings table, so behaviour such as the
 * "stuck order" limit (D32) changes without a redeploy. The admin screen and /config endpoint come with M27.
 */
@Repository
public class AppSettings {

    private final DatabaseClient db;

    public AppSettings(DatabaseClient db) {
        this.db = db;
    }

    /** A numeric setting, or {@code fallback} if it is missing or not a number. */
    public Mono<Integer> integer(String key, int fallback) {
        return db.sql("SELECT value::text FROM app_settings WHERE key = :key")
                .bind("key", key)
                .map((row, meta) -> row.get(0, String.class))
                .one()
                .map(text -> {
                    try {
                        return Integer.parseInt(text.strip());
                    } catch (NumberFormatException e) {
                        return fallback;
                    }
                })
                .defaultIfEmpty(fallback);
    }
}
