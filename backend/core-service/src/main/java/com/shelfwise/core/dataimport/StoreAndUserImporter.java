package com.shelfwise.core.dataimport;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.shelfwise.core.dataimport.CsvFile.Row;
import com.shelfwise.core.dataimport.ImportLog.Outcome;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Imports stores.csv and users.csv. Insert-only: a record whose ID already exists is skipped,
 * so changes made in the app are never overwritten (D28).
 */
@Component
class StoreAndUserImporter {

    static final String STORES_FILE = "stores.csv";
    static final String USERS_FILE = "users.csv";
    private static final Set<String> ROLES = Set.of("ADMIN", "STORE_MANAGER", "FINANCE_APPROVER");

    private final DatabaseClient db;
    private final ImportLog log;
    private final PasswordEncoder passwordEncoder;

    StoreAndUserImporter(DatabaseClient db, ImportLog log, PasswordEncoder passwordEncoder) {
        this.db = db;
        this.log = log;
        this.passwordEncoder = passwordEncoder;
    }

    Mono<FileResult> importStores(long runId, List<Row> rows) {
        return Flux.fromIterable(rows)
                .concatMap(row -> db.sql("""
                                INSERT INTO stores (id, name, city, state, opened_on)
                                VALUES (:id, :name, :city, :state, :openedOn)
                                ON CONFLICT (id) DO NOTHING
                                """)
                        .bind("id", row.get("store_id"))
                        .bind("name", row.get("name"))
                        .bind("city", row.get("city"))
                        .bind("state", row.get("state"))
                        .bind("openedOn", LocalDate.parse(row.get("opened_on")))
                        .fetch().rowsUpdated()
                        .flatMap(added -> added == 1
                                ? log.saveRaw(runId, STORES_FILE, row.line(), row.get("store_id"), row.values()).thenReturn(true)
                                : Mono.just(false)))
                .reduce(FileResult.of(STORES_FILE), (result, added) -> added ? result.plusAdded() : result.plusSkipped());
    }

    /**
     * Users get the demo starting password (D25), hashed. Inactive users are imported as inactive
     * and cannot sign in (D20, D27). Both managers of a store become full managers (D26).
     */
    Mono<FileResult> importUsers(long runId, List<Row> rows, String demoPassword) {
        // Hash once: BCrypt is slow by design and every imported user starts with the same password.
        Mono<String> demoHash = Mono.fromCallable(() -> passwordEncoder.encode(demoPassword))
                .subscribeOn(Schedulers.boundedElastic())
                .cache();

        return Flux.fromIterable(rows)
                .concatMap(row -> validateUser(row) == null
                        ? demoHash.flatMap(hash -> insertUser(runId, row, hash))
                        : log.issue(runId, "USERS", Outcome.REJECTED, "USER", row.get("user_id"),
                                        "Line " + row.line() + ": " + validateUser(row))
                                .thenReturn(Added.REJECTED))
                .reduce(FileResult.of(USERS_FILE), (result, added) -> switch (added) {
                    case ADDED -> result.plusAdded();
                    case SKIPPED -> result.plusSkipped();
                    case REJECTED -> result.plusRejected();
                });
    }

    /** D26: stores.csv names one manager per store; it becomes the store's main contact (a label only). */
    Mono<Void> linkMainContacts(long runId, List<Row> storeRows, List<Row> userRows) {
        Map<String, Long> managersPerStore = userRows.stream()
                .filter(u -> "STORE_MANAGER".equals(u.get("role")))
                .collect(Collectors.groupingBy(u -> u.get("store_id"), Collectors.counting()));
        return Flux.fromIterable(storeRows)
                .concatMap(store -> db.sql("""
                                UPDATE stores SET main_contact_user_id = :userId
                                WHERE id = :storeId AND main_contact_user_id IS NULL
                                """)
                        .bind("userId", store.get("manager_user_id"))
                        .bind("storeId", store.get("store_id"))
                        .fetch().rowsUpdated()
                        .flatMap(updated -> updated == 1 && managersPerStore.getOrDefault(store.get("store_id"), 0L) > 1
                                ? log.issue(runId, "D26", Outcome.INFO, "STORE", store.get("store_id"),
                                        managersPerStore.get(store.get("store_id")) + " store managers; both have full access. "
                                                + store.get("manager_user_id") + " is shown as main contact only.")
                                : Mono.empty()))
                .then();
    }

    private enum Added { ADDED, SKIPPED, REJECTED }

    private Mono<Added> insertUser(long runId, Row row, String hash) {
        boolean active = Boolean.parseBoolean(row.get("active"));
        String storeId = row.get("store_id");
        DatabaseClient.GenericExecuteSpec insert = db.sql("""
                        INSERT INTO users (id, name, email, role, store_id, active, password_hash, must_change_password)
                        VALUES (:id, :name, :email, :role, :storeId, :active, :hash, true)
                        ON CONFLICT (id) DO NOTHING
                        """)
                .bind("id", row.get("user_id"))
                .bind("name", row.get("name"))
                .bind("email", row.get("email"))
                .bind("role", row.get("role"))
                .bind("active", active)
                .bind("hash", hash);
        insert = blank(storeId) ? insert.bindNull("storeId", String.class) : insert.bind("storeId", storeId);
        return insert.fetch().rowsUpdated()
                .flatMap(added -> {
                    if (added == 0) {
                        return Mono.just(Added.SKIPPED);
                    }
                    Mono<Void> notes = log.saveRaw(runId, USERS_FILE, row.line(), row.get("user_id"), row.values())
                            .then(log.issue(runId, "D25", Outcome.INFO, "USER", row.get("user_id"),
                                    "Given the demo starting password (stored hashed)"));
                    if (!active) {
                        notes = notes.then(log.issue(runId, "D27", Outcome.INFO, "USER", row.get("user_id"),
                                "Imported as inactive: cannot sign in or approve; past approvals stay valid (D20)"));
                    }
                    return notes.thenReturn(Added.ADDED);
                });
    }

    /** Returns null when the row is valid, otherwise the reason it is rejected. */
    private static String validateUser(Row row) {
        if (blank(row.get("user_id")) || blank(row.get("name")) || blank(row.get("email"))) {
            return "user_id, name and email are required";
        }
        if (!ROLES.contains(row.get("role"))) {
            return "unknown role '" + row.get("role") + "'";
        }
        if (!Set.of("true", "false").contains(row.get("active"))) {
            return "active must be true or false, was '" + row.get("active") + "'";
        }
        boolean isManager = "STORE_MANAGER".equals(row.get("role"));
        if (isManager == blank(row.get("store_id"))) {
            return "a store manager needs a store; other roles must not have one";
        }
        return null;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
