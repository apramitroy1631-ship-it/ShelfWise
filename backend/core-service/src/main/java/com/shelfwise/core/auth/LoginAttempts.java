package com.shelfwise.core.auth;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Limits password guessing (review issue 7). Failed sign-ins are counted per email and per IP address within a
 * 15-minute window; reaching the limit locks that email (or IP) for 5 minutes. A successful sign-in clears the
 * email's count. Unknown emails are counted the same way, so the limit does not reveal which emails exist.
 */
@Component
public class LoginAttempts {

    /** Tries per email before the wait: a person who mistypes gets 5 chances. */
    static final int MAX_PER_EMAIL = 5;
    /** Tries per IP address: higher, because a whole store or office can share one address. */
    static final int MAX_PER_IP = 30;
    static final Duration WINDOW = Duration.ofMinutes(15);
    static final Duration LOCK = Duration.ofMinutes(5);

    private final DatabaseClient db;

    public LoginAttempts(DatabaseClient db) {
        this.db = db;
    }

    static String emailKey(String email) {
        return "email:" + email.trim().toLowerCase(Locale.ROOT);
    }

    static String ipKey(String ip) {
        return "ip:" + ip;
    }

    /** How long until sign-in is allowed again for any of these keys; empty if none is locked. */
    public Mono<Duration> lockedFor(List<String> keys) {
        return db.sql("SELECT ceil(extract(epoch FROM max(locked_until) - now()))::bigint AS seconds FROM login_attempts"
                        + " WHERE key IN (:keys) AND locked_until > now()")
                .bind("keys", keys)
                .map((row, meta) -> {
                    Long seconds = row.get("seconds", Long.class);
                    return seconds == null ? Duration.ZERO : Duration.ofSeconds(seconds);
                })
                .one()
                .filter(d -> !d.isZero());
    }

    /** Counts one failure for the email and one for the IP address, locking any that reaches its limit. */
    public Mono<Void> recordFailure(String emailKey, String ipKey) {
        return Flux.concat(count(emailKey, MAX_PER_EMAIL), count(ipKey, MAX_PER_IP)).then();
    }

    public Mono<Void> clear(String key) {
        return db.sql("DELETE FROM login_attempts WHERE key = :key").bind("key", key).then();
    }

    private Mono<Void> count(String key, int max) {
        return db.sql("""
                        INSERT INTO login_attempts AS a (key, failures, window_start) VALUES (:key, 1, now())
                        ON CONFLICT (key) DO UPDATE SET
                            failures = CASE WHEN a.window_start < now() - make_interval(secs => :window) THEN 1 ELSE a.failures + 1 END,
                            window_start = CASE WHEN a.window_start < now() - make_interval(secs => :window) THEN now() ELSE a.window_start END
                        RETURNING failures
                        """)
                .bind("key", key).bind("window", (double) WINDOW.toSeconds())
                .map((row, meta) -> row.get("failures", Integer.class))
                .one()
                .flatMap(failures -> failures < max ? Mono.<Void>empty()
                        : db.sql("UPDATE login_attempts SET locked_until = now() + make_interval(secs => :lock), failures = 0,"
                                        + " window_start = now() WHERE key = :key")
                                .bind("key", key).bind("lock", (double) LOCK.toSeconds()).then());
    }
}
