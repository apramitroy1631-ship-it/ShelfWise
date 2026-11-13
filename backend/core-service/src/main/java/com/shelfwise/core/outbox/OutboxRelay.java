package com.shelfwise.core.outbox;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

import com.rabbitmq.client.AMQP;

import jakarta.annotation.PreDestroy;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.ExchangeSpecification;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.Sender;
import tools.jackson.databind.ObjectMapper;

/**
 * Publishes the outbox to RabbitMQ (DL-03). Every couple of seconds it takes a batch of unpublished events,
 * sends them with publisher confirms (the broker says "stored"), and only then marks them published, all in
 * one database transaction. If anything fails, the transaction rolls back and the same events are sent again
 * on the next run, so delivery is at-least-once and the notification service ignores repeats by event id.
 * <p>
 * {@code FOR UPDATE SKIP LOCKED}: if two copies of the core service run, each takes different rows, so an event
 * is not published twice by two relays at the same time.
 * <p>
 * Markers ({@link #SNAPSHOT_EVENTS}) say "this situation changed"; the relay replaces them with the current
 * counts at publish time and sends one per store (or one for finance) per batch, however many markers piled up.
 */
@Component
@ConditionalOnProperty(name = "shelfwise.messaging.relay-enabled", havingValue = "true")
public class OutboxRelay {

    static final Set<String> SNAPSHOT_EVENTS = Set.of("StockHealthChanged", "ApprovalQueueChanged", "StuckWorkChanged", "HeadOfficeChanged");
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private record Pending(UUID id, String aggregateType, String aggregateId, String eventType, String payload, OffsetDateTime createdAt) {
    }

    private final DatabaseClient db;
    private final Sender sender;
    private final StateSnapshots snapshots;
    private final ObjectMapper json;
    private final MessagingProperties properties;
    private final TransactionalOperator transaction;
    private final AtomicBoolean exchangeDeclared = new AtomicBoolean();
    private final AtomicBoolean failing = new AtomicBoolean();
    private volatile Disposable loop;

    public OutboxRelay(DatabaseClient db, Sender sender, StateSnapshots snapshots, ObjectMapper json,
                       MessagingProperties properties, ReactiveTransactionManager transactionManager) {
        this.db = db;
        this.sender = sender;
        this.snapshots = snapshots;
        this.json = json;
        this.properties = properties;
        this.transaction = TransactionalOperator.create(transactionManager);
    }

    /** Starts after the start-up import, so day one begins from the imported counts (D7). */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        log.info("Outbox relay started: {}", properties);
        loop = markStartingState()
                .thenMany(Flux.interval(Duration.ZERO, properties.relayInterval())
                        .onBackpressureDrop()
                        .concatMap(tick -> publishBatch().onErrorResume(this::logFailure)))
                .subscribe();
    }

    @PreDestroy
    public void stop() {
        if (loop != null) {
            loop.dispose();
        }
    }

    /** One marker per store (stock, stuck work), one for finance and one for head office: the notification service always starts from the current counts. */
    public Mono<Void> markStartingState() {
        return db.sql("""
                        INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload)
                        SELECT gen_random_uuid(), 'STORE', id, 'StockHealthChanged', '{}'::jsonb FROM stores
                        UNION ALL
                        SELECT gen_random_uuid(), 'FINANCE', 'FINANCE', 'ApprovalQueueChanged', '{}'::jsonb
                        UNION ALL
                        SELECT gen_random_uuid(), 'STORE', id, 'StuckWorkChanged', '{}'::jsonb FROM stores
                        UNION ALL
                        SELECT gen_random_uuid(), 'ADMIN', 'ADMIN', 'HeadOfficeChanged', '{}'::jsonb
                        """)
                .then();
    }

    /** Publishes one batch. Returns how many outbox rows were marked published (0 when there was nothing to do). */
    public Mono<Integer> publishBatch() {
        return db.sql("""
                        SELECT id, aggregate_type, aggregate_id, event_type, payload::text AS payload, created_at
                        FROM outbox_events WHERE published_at IS NULL
                        ORDER BY created_at, id LIMIT :limit
                        FOR UPDATE SKIP LOCKED
                        """)
                .bind("limit", properties.batchSize())
                .map((row, meta) -> new Pending(row.get("id", UUID.class), row.get("aggregate_type", String.class),
                        row.get("aggregate_id", String.class), row.get("event_type", String.class), row.get("payload", String.class),
                        row.get("created_at", OffsetDateTime.class)))
                .all()
                .collectList()
                .flatMap(batch -> batch.isEmpty() ? Mono.just(0)
                        : send(batch).then(markPublished(batch)).thenReturn(batch.size()))
                .as(transaction::transactional)
                .doOnNext(count -> {
                    if (failing.compareAndSet(true, false)) {
                        log.info("Outbox relay recovered; publishing again");
                    }
                });
    }

    private Mono<Void> send(List<Pending> batch) {
        List<Pending> toSend = new ArrayList<>();
        Map<String, Pending> newestMarker = new LinkedHashMap<>();
        for (Pending p : batch) {
            if (SNAPSHOT_EVENTS.contains(p.eventType())) {
                newestMarker.put(p.eventType() + "|" + p.aggregateId(), p);
            } else {
                toSend.add(p);
            }
        }
        toSend.addAll(newestMarker.values());
        return Flux.fromIterable(toSend)
                .concatMap(this::toMessage)
                .collectList()
                .flatMap(messages -> declareExchange()
                        .thenMany(sender.sendWithPublishConfirms(Flux.fromIterable(messages)))
                        .filter(result -> !result.isAck())
                        .count())
                .timeout(Duration.ofSeconds(15))
                .flatMap(notConfirmed -> notConfirmed == 0 ? Mono.<Void>empty()
                        : Mono.error(new IllegalStateException(notConfirmed + " events were not confirmed by RabbitMQ")));
    }

    private Mono<OutboundMessage> toMessage(Pending p) {
        Mono<Map<String, Object>> payload = switch (p.eventType()) {
            case "StockHealthChanged" -> snapshots.stockHealth(p.aggregateId());
            case "ApprovalQueueChanged" -> snapshots.approvalQueue();
            case "StuckWorkChanged" -> snapshots.stuckWork(p.aggregateId());
            case "HeadOfficeChanged" -> snapshots.headOffice();
            default -> Mono.fromCallable(() -> readMap(p.payload()));
        };
        return payload.map(body -> {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("id", p.id().toString());
            envelope.put("type", p.eventType());
            envelope.put("aggregateType", p.aggregateType());
            envelope.put("aggregateId", p.aggregateId());
            envelope.put("occurredAt", p.createdAt().toString());
            envelope.put("payload", body);
            AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
                    .messageId(p.id().toString())
                    .type(p.eventType())
                    .contentType("application/json")
                    .deliveryMode(2)                                   // persistent: survives a broker restart
                    .timestamp(Date.from(p.createdAt().toInstant()))
                    .build();
            return new OutboundMessage(properties.exchange(), p.eventType(), props, json.writeValueAsBytes(envelope));
        });
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String text) {
        return json.readValue(text, Map.class);
    }

    private Mono<Void> declareExchange() {
        if (exchangeDeclared.get()) {
            return Mono.empty();
        }
        return sender.declareExchange(ExchangeSpecification.exchange(properties.exchange()).type("topic").durable(true))
                .doOnSuccess(ok -> exchangeDeclared.set(true))
                .then();
    }

    private Mono<Void> markPublished(List<Pending> batch) {
        return db.sql("UPDATE outbox_events SET published_at = now() WHERE id = ANY(:ids)")
                .bind("ids", batch.stream().map(Pending::id).toArray(UUID[]::new))
                .then();
    }

    /** Logs the first failure of a streak as a warning (e.g. broker down), then stays quiet until it recovers. */
    private Mono<Integer> logFailure(Throwable e) {
        if (failing.compareAndSet(false, true)) {
            log.warn("Outbox relay can't publish yet; events stay in the outbox and will be retried: {}", e.toString());
        } else {
            log.debug("Outbox relay still failing: {}", e.toString());
        }
        exchangeDeclared.set(false);
        return Mono.just(0);
    }
}
