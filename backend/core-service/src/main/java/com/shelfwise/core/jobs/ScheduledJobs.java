package com.shelfwise.core.jobs;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

import com.shelfwise.core.audit.AuditLog;
import com.shelfwise.core.config.AppSettings;

import io.r2dbc.postgresql.codec.Json;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/**
 * The two nightly jobs from the brief (M23). Both run on a schedule (IST night, configurable) and can be started
 * by an admin from the API for a demo or after a fix.
 * <ul>
 *   <li><b>Expire stale drafts (D22):</b> a draft PO untouched for longer than the remote-config limit (default
 *       14 days) becomes EXPIRED. Imported drafts count from their creation date, so all 20 expire on the first run.</li>
 *   <li><b>Low-stock recount:</b> recounts each store's out-of-stock and running-low items and sends the current
 *       counts to the notification service. This also corrects anything a live update missed (e.g. a product
 *       received into a store for the first time).</li>
 * </ul>
 * Each run is ONE transaction that starts with a Postgres advisory lock: if two copies of the core service wake
 * up at the same moment, only one does the work and the other records "SKIPPED". Every run is written to job_runs.
 */
@Component
public class ScheduledJobs {

    public enum Job { EXPIRE_DRAFTS, LOW_STOCK_RECOUNT }

    public record Run(long id, String job, String trigger, String outcome, int affected, Map<String, Object> details,
                      OffsetDateTime startedAt, OffsetDateTime finishedAt) {
    }

    private static final Logger log = LoggerFactory.getLogger(ScheduledJobs.class);
    /** Advisory-lock keys: any fixed numbers, one per job. */
    private static final Map<Job, Long> LOCK = Map.of(Job.EXPIRE_DRAFTS, 23_001L, Job.LOW_STOCK_RECOUNT, 23_002L);

    private final DatabaseClient db;
    private final AppSettings settings;
    private final AuditLog audit;
    private final ObjectMapper json;
    private final TransactionalOperator transaction;

    public ScheduledJobs(DatabaseClient db, AppSettings settings, AuditLog audit, ObjectMapper json,
                         ReactiveTransactionManager transactionManager) {
        this.db = db;
        this.settings = settings;
        this.audit = audit;
        this.json = json;
        this.transaction = TransactionalOperator.create(transactionManager);
    }

    // --- schedule ------------------------------------------------------------------

    /** 01:30 IST by default; set JOBS_EXPIRE_DRAFTS_CRON to change it, or "-" to switch it off. */
    @Scheduled(cron = "${shelfwise.jobs.expire-drafts-cron}", zone = "Asia/Kolkata")
    public Mono<Run> nightlyExpireDrafts() {
        return run(Job.EXPIRE_DRAFTS, "SCHEDULE", null);
    }

    /** 01:45 IST by default; JOBS_LOW_STOCK_CRON. */
    @Scheduled(cron = "${shelfwise.jobs.low-stock-cron}", zone = "Asia/Kolkata")
    public Mono<Run> nightlyLowStockRecount() {
        return run(Job.LOW_STOCK_RECOUNT, "SCHEDULE", null);
    }

    // --- running -------------------------------------------------------------------

    /** Runs one job now. {@code startedBy} = the admin's user id, or null for the schedule. */
    public Mono<Run> run(Job job, String trigger, String startedBy) {
        Mono<Run> work = db.sql("SELECT pg_try_advisory_xact_lock(:key)").bind("key", LOCK.get(job))
                .map((row, meta) -> Boolean.TRUE.equals(row.get(0, Boolean.class))).one()
                .flatMap(gotLock -> {
                    if (!gotLock) {
                        return record(job, trigger, startedBy, "SKIPPED", 0, Map.of("reason", "another instance is running this job"));
                    }
                    Mono<Result> body = job == Job.EXPIRE_DRAFTS ? expireDrafts() : lowStockRecount();
                    return body.flatMap(r -> record(job, trigger, startedBy, "DONE", r.affected(), r.details()));
                })
                .as(transaction::transactional);
        return work
                .doOnNext(r -> log.info("Job {} ({}): {} , {} affected", job, trigger, r.outcome(), r.affected()))
                .onErrorResume(e -> {
                    log.error("Job {} failed: {}", job, e.toString());
                    return record(job, trigger, startedBy, "FAILED", 0, Map.of("error", e.getClass().getSimpleName()));
                });
    }

    private record Result(int affected, Map<String, Object> details) {
    }

    /** D22 part 1. "Untouched" = since creation for imported drafts (no edit history), since last change for app drafts. */
    private Mono<Result> expireDrafts() {
        return settings.integer("purchaseOrders.draftExpiryDays", 14).flatMap(days -> db.sql("""
                        UPDATE purchase_orders SET status = 'EXPIRED', version = version + 1, updated_at = now()
                        WHERE status = 'DRAFT'
                          AND (CASE WHEN source = 'IMPORT' THEN created_at ELSE updated_at END) < now() - make_interval(days => :days)
                        RETURNING id
                        """)
                .bind("days", days)
                .map((row, meta) -> row.get(0, String.class))
                .all()
                .collectList()
                .flatMap(ids -> Flux.fromIterable(ids)
                        .concatMap(id -> audit.record(null, "PO_EXPIRED", "PURCHASE_ORDER", id,
                                Map.of("status", "DRAFT"), Map.of("status", "EXPIRED"), "Draft untouched for more than " + days + " days (D22)"))
                        .then(Mono.fromCallable(() -> {
                            Map<String, Object> details = new LinkedHashMap<>();
                            details.put("expiryDays", days);
                            details.put("expired", ids);
                            return new Result(ids.size(), details);
                        }))));
    }

    /**
     * Recounts each store and writes one marker per store; the outbox relay turns each into the store's current
     * counts for the notification service (D7). The counts are also kept in the run's details.
     */
    private Mono<Result> lowStockRecount() {
        return db.sql("""
                        SELECT s.id,
                               count(i.sku) FILTER (WHERE p.status = 'ACTIVE' AND i.quantity = 0) AS out_count,
                               count(i.sku) FILTER (WHERE p.status = 'ACTIVE' AND i.quantity > 0 AND i.quantity <= i.reorder_level) AS low_count
                        FROM stores s
                        LEFT JOIN inventory i ON i.store_id = s.id
                        LEFT JOIN products p ON p.sku = i.sku
                        GROUP BY s.id ORDER BY s.id
                        """)
                .map((row, meta) -> Map.<String, Object>of("store", row.get("id", String.class),
                        "outOfStock", row.get("out_count", Long.class), "runningLow", row.get("low_count", Long.class)))
                .all()
                .collectList()
                .flatMap(stores -> db.sql("""
                                INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload)
                                SELECT gen_random_uuid(), 'STORE', id, 'StockHealthChanged', '{"reason": "nightly recount"}'::jsonb FROM stores
                                UNION ALL
                                -- orders and POs become stuck just by getting older, so the nightly run refreshes these too (D32, D22)
                                SELECT gen_random_uuid(), 'STORE', id, 'StuckWorkChanged', '{"reason": "nightly recount"}'::jsonb FROM stores
                                UNION ALL
                                -- head office's stock health is a once-a-day summary (D7)
                                SELECT gen_random_uuid(), 'ADMIN', 'ADMIN', 'HeadOfficeChanged', '{"reason": "nightly recount"}'::jsonb
                                """)
                        .then()
                        .thenReturn(new Result(stores.size(), Map.of("stores", stores))));
    }

    private Mono<Run> record(Job job, String trigger, String startedBy, String outcome, int affected, Map<String, Object> details) {
        DatabaseClient.GenericExecuteSpec spec = db.sql("""
                        INSERT INTO job_runs (job, trigger, started_by, finished_at, outcome, affected, details)
                        VALUES (:job, :trigger, :by, clock_timestamp(), :outcome, :affected, :details)
                        RETURNING id, started_at, finished_at
                        """)
                .bind("job", job.name()).bind("trigger", trigger).bind("outcome", outcome).bind("affected", affected)
                .bind("details", Json.of(json.writeValueAsString(details)));
        spec = startedBy == null ? spec.bindNull("by", String.class) : spec.bind("by", startedBy);
        return spec.map((row, meta) -> new Run(row.get("id", Long.class), job.name(), trigger, outcome, affected, details,
                        row.get("started_at", OffsetDateTime.class), row.get("finished_at", OffsetDateTime.class)))
                .one();
    }

    /** The latest runs, newest first, for the admin screen and monitoring. */
    @SuppressWarnings("unchecked")
    public Flux<Run> recent(int limit) {
        return db.sql("""
                        SELECT id, job, trigger, outcome, affected, details::text AS details, started_at, finished_at
                        FROM job_runs ORDER BY started_at DESC, id DESC LIMIT :limit
                        """)
                .bind("limit", limit)
                .map((row, meta) -> new Run(row.get("id", Long.class), row.get("job", String.class), row.get("trigger", String.class),
                        row.get("outcome", String.class), row.get("affected", Integer.class),
                        json.readValue(row.get("details", String.class), Map.class),
                        row.get("started_at", OffsetDateTime.class), row.get("finished_at", OffsetDateTime.class)))
                .all();
    }

    static List<String> names() {
        return List.of(Job.EXPIRE_DRAFTS.name(), Job.LOW_STOCK_RECOUNT.name());
    }
}
