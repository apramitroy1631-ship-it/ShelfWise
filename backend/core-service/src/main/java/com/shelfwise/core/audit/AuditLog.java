package com.shelfwise.core.audit;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;

import io.r2dbc.postgresql.codec.Json;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/**
 * Who changed what, when and why. Written in the same transaction as the change itself,
 * so a change and its audit entry are saved together or not at all.
 */
@Repository
public class AuditLog {

    private final DatabaseClient db;
    private final ObjectMapper json;

    public AuditLog(DatabaseClient db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    public Mono<Void> record(String actorUserId, String action, String entityType, String entityId,
                             Object before, Object after, String reason) {
        DatabaseClient.GenericExecuteSpec spec = db.sql("""
                        INSERT INTO audit_log (actor_user_id, action, entity_type, entity_id, before_value, after_value, reason)
                        VALUES (:actor, :action, :entityType, :entityId, :before, :after, :reason)
                        """)
                .bind("actor", actorUserId)
                .bind("action", action)
                .bind("entityType", entityType)
                .bind("entityId", entityId);
        spec = before == null ? spec.bindNull("before", Json.class) : spec.bind("before", Json.of(json.writeValueAsString(before)));
        spec = after == null ? spec.bindNull("after", Json.class) : spec.bind("after", Json.of(json.writeValueAsString(after)));
        spec = reason == null ? spec.bindNull("reason", String.class) : spec.bind("reason", reason);
        return spec.then();
    }
}
