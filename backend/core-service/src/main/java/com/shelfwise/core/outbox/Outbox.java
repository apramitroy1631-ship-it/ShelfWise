package com.shelfwise.core.outbox;

import java.util.Map;
import java.util.UUID;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;

import io.r2dbc.postgresql.codec.Json;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/**
 * Transactional outbox (DL-03). An event is written to the outbox_events table in the SAME transaction
 * as the change it describes, so "PO approved" can never be saved without its event (or the other way round).
 * A relay (M21) later publishes unpublished rows to RabbitMQ and marks them published; the notification
 * service reads them from there. The event shapes are documented in docs/events.md.
 */
@Repository
public class Outbox {

    private final DatabaseClient db;
    private final ObjectMapper json;

    public Outbox(DatabaseClient db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    public Mono<Void> add(String aggregateType, String aggregateId, String eventType, Map<String, Object> payload) {
        return db.sql("""
                        INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload)
                        VALUES (:id, :type, :aggregateId, :eventType, :payload)
                        """)
                .bind("id", UUID.randomUUID())
                .bind("type", aggregateType)
                .bind("aggregateId", aggregateId)
                .bind("eventType", eventType)
                .bind("payload", Json.of(json.writeValueAsString(payload)))
                .then();
    }
}
