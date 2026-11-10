package com.shelfwise.core.dataimport;

import java.util.Map;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;

import io.r2dbc.postgresql.codec.Json;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/**
 * Bookkeeping for each import run: the run itself, an untouched copy of every record added
 * ("keep the original"), and one row per data decision applied (the basis of the import report).
 */
@Repository
public class ImportLog {

    public enum Outcome { INFO, CORRECTED, FLAGGED, REJECTED, SKIPPED }

    private final DatabaseClient db;
    private final ObjectMapper json;

    public ImportLog(DatabaseClient db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    public Mono<Long> startRun(String mode) {
        return db.sql("INSERT INTO import_runs (mode, status) VALUES (:mode, 'RUNNING') RETURNING id")
                .bind("mode", mode)
                .map((row, meta) -> row.get("id", Long.class))
                .one();
    }

    public Mono<Void> finishRun(long runId, String status, Object summary) {
        return db.sql("UPDATE import_runs SET status = :status, finished_at = now(), summary = :summary WHERE id = :id")
                .bind("status", status)
                .bind("summary", toJson(summary))
                .bind("id", runId)
                .then();
    }

    public Mono<Void> saveRaw(long runId, String sourceFile, int position, String naturalKey, Map<String, String> payload) {
        return db.sql("""
                INSERT INTO raw_import_records (run_id, source_file, position, natural_key, payload)
                VALUES (:runId, :file, :position, :key, :payload)
                """)
                .bind("runId", runId)
                .bind("file", sourceFile)
                .bind("position", position)
                .bind("key", naturalKey)
                .bind("payload", toJson(payload))
                .then();
    }

    public Mono<Void> issue(long runId, String decision, Outcome outcome, String entityType, String entityId, String message) {
        return db.sql("""
                INSERT INTO import_issues (run_id, decision_code, outcome, entity_type, entity_id, message)
                VALUES (:runId, :decision, :outcome, :entityType, :entityId, :message)
                """)
                .bind("runId", runId)
                .bind("decision", decision)
                .bind("outcome", outcome.name())
                .bind("entityType", entityType)
                .bind("entityId", entityId)
                .bind("message", message)
                .then();
    }

    private Json toJson(Object value) {
        return Json.of(json.writeValueAsString(value));
    }
}
