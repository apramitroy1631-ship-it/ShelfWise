package com.shelfwise.core.observability;

import java.time.Duration;
import java.time.OffsetDateTime;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.ReactiveHealthIndicator;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

/**
 * Monitoring for the event pipeline (M28). Events wait in the outbox until the relay has published them to RabbitMQ;
 * if the oldest one has waited more than 5 minutes, the relay or the broker is broken and notifications have stopped.
 * Shown as "outbox" on /actuator/health (backlog and age for signed-in callers). It is NOT part of the readiness
 * check: the core keeps selling while RabbitMQ is down (DL-03), so a platform must not take it out of service for this.
 */
@Component   // shown as "outbox" (Spring drops the HealthIndicator suffix)
public class OutboxHealthIndicator implements ReactiveHealthIndicator {

    static final Duration STUCK_AFTER = Duration.ofMinutes(5);

    private final DatabaseClient db;

    public OutboxHealthIndicator(DatabaseClient db) {
        this.db = db;
    }

    @Override
    public Mono<Health> health() {
        return db.sql("SELECT count(*) AS waiting, min(created_at) AS oldest FROM outbox_events WHERE published_at IS NULL")
                .map((row, meta) -> {
                    long waiting = row.get("waiting", Long.class);
                    OffsetDateTime oldest = row.get("oldest", OffsetDateTime.class);
                    long ageSeconds = oldest == null ? 0 : Duration.between(oldest, OffsetDateTime.now()).toSeconds();
                    Health.Builder health = ageSeconds > STUCK_AFTER.toSeconds() ? Health.down() : Health.up();
                    return health.withDetail("waiting", waiting).withDetail("oldestWaitingSeconds", ageSeconds).build();
                })
                .one();
    }
}
