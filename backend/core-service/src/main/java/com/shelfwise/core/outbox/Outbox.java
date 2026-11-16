package com.shelfwise.core.outbox;

import java.util.Map;
import java.util.UUID;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;

import io.r2dbc.postgresql.codec.Json;
import com.shelfwise.core.observability.RequestIdFilter;

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

    /** Marker: a store's stuck orders or stuck POs may have changed (D32, D22). The relay sends the current counts. */
    public Mono<Void> stuckWorkChanged(String storeId) {
        return add("STORE", storeId, "StuckWorkChanged", Map.of());
    }

    /** Marker: what head office sees may have changed (orders waiting for review, D10). The relay sends the current counts. */
    public Mono<Void> headOfficeChanged() {
        return add("ADMIN", "ADMIN", "HeadOfficeChanged", Map.of());
    }

    /** The request that caused the event (M28) is saved with it, so both services log the same request id. */
    public Mono<Void> add(String aggregateType, String aggregateId, String eventType, Map<String, Object> payload) {
        return Mono.deferContextual(context -> {
            DatabaseClient.GenericExecuteSpec spec = db.sql("""
                            INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, request_id)
                            VALUES (:id, :type, :aggregateId, :eventType, :payload, :requestId)
                            """)
                    .bind("id", UUID.randomUUID())
                    .bind("type", aggregateType)
                    .bind("aggregateId", aggregateId)
                    .bind("eventType", eventType)
                    .bind("payload", Json.of(json.writeValueAsString(payload)));
            String requestId = context.getOrDefault(RequestIdFilter.KEY, null);
            spec = requestId == null ? spec.bindNull("requestId", String.class) : spec.bind("requestId", requestId);
            return spec.then();
        });
    }
}
