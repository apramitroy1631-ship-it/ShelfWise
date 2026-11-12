package com.shelfwise.core.config;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ResponseStatusException;

import com.shelfwise.core.audit.AuditLog;
import com.shelfwise.core.auth.UserAccount;

import io.r2dbc.postgresql.codec.Json;
import io.r2dbc.spi.Readable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Remote configuration (DL-06, M27). Admins change settings here; the services read them on every request
 * (through {@link AppSettings}) and the apps fetch the public ones from /api/config about every minute, so a
 * change takes effect without a redeploy or an app-store release.
 * <p>
 * Every value is checked against its rule before saving, changes carry the version the admin saw (two admins
 * can't overwrite each other), and every change is audited with the old and new value.
 */
@Service
public class SettingsService {

    /** What each setting may hold. Only these keys exist; nobody can invent new ones from the API. */
    sealed interface Rule permits Whole, Flag, Text {
        String describe();
    }

    record Whole(int min, int max) implements Rule {
        public String describe() { return "a whole number from " + min + " to " + max; }
    }

    record Flag() implements Rule {
        public String describe() { return "true or false"; }
    }

    /** Text up to {@code maxLength} characters, or null (e.g. "no banner"). */
    record Text(int maxLength) implements Rule {
        public String describe() { return "text up to " + maxLength + " characters, or null to hide it"; }
    }

    static final Map<String, Rule> RULES = Map.of(
            "lowStock.defaultReorderLevel", new Whole(0, 10_000),
            "purchaseOrders.draftExpiryDays", new Whole(1, 365),
            "purchaseOrders.stuckAfterDays", new Whole(1, 365),
            "purchaseOrders.secondApprovalAboveInr", new Whole(0, 10_000_000),
            "orders.stuckAfterDays", new Whole(1, 365),
            "auth.forcePasswordChangeOnFirstLogin", new Flag(),
            "ui.maintenanceBanner", new Text(200),
            "features.adminReviewQueue", new Flag());

    /** Settings the apps need (banner, feature flags); safe to show before sign-in. */
    static boolean isPublic(String key) {
        return key.startsWith("ui.") || key.startsWith("features.");
    }

    public record Setting(String key, Object value, String description, String rule, boolean isPublic,
                          String updatedBy, String updatedByName, OffsetDateTime updatedAt, long version) {
    }

    /** What the apps poll: the public values, and when anything last changed (so an app can tell what's new). */
    public record PublicConfig(Map<String, Object> values, OffsetDateTime updatedAt) {
    }

    private final DatabaseClient db;
    private final ObjectMapper json;
    private final AuditLog audit;
    private final TransactionalOperator transaction;

    public SettingsService(DatabaseClient db, ObjectMapper json, AuditLog audit, ReactiveTransactionManager transactionManager) {
        this.db = db;
        this.json = json;
        this.audit = audit;
        this.transaction = TransactionalOperator.create(transactionManager);
    }

    public Flux<Setting> all() {
        return db.sql("""
                        SELECT s.key, s.value::text AS value, s.description, s.updated_by, u.name AS updated_by_name, s.updated_at, s.version
                        FROM app_settings s LEFT JOIN users u ON u.id = s.updated_by
                        ORDER BY s.key
                        """)
                .map((row, meta) -> toSetting(row))
                .all()
                .filter(s -> RULES.containsKey(s.key()));
    }

    public Mono<PublicConfig> publicConfig() {
        return all().filter(Setting::isPublic).collectList().map(list -> {
            Map<String, Object> values = new LinkedHashMap<>();
            OffsetDateTime latest = null;
            for (Setting s : list) {
                values.put(s.key(), s.value());
                latest = latest == null || s.updatedAt().isAfter(latest) ? s.updatedAt() : latest;
            }
            return new PublicConfig(values, latest);
        });
    }

    /** Admin changes one setting. 400 = not allowed for this setting, 404 = no such setting, 409 = changed meanwhile. */
    public Mono<Setting> update(UserAccount admin, String key, JsonNode value, long version) {
        if (admin.role() != UserAccount.Role.ADMIN) {
            return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "Only admins change settings"));
        }
        Rule rule = RULES.get(key);
        if (rule == null) {
            return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "No setting '" + key + "'"));
        }
        JsonNode clean;
        try {
            clean = check(rule, value);
        } catch (IllegalArgumentException e) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "'" + key + "' must be " + rule.describe()));
        }
        String newValue = json.writeValueAsString(clean);
        return findOne(key)
                .flatMap(before -> db.sql("""
                                UPDATE app_settings SET value = :value, updated_by = :user, updated_at = now(), version = version + 1
                                WHERE key = :key AND version = :version
                                """)
                        .bind("value", Json.of(newValue)).bind("user", admin.id()).bind("key", key).bind("version", version)
                        .fetch().rowsUpdated()
                        .flatMap(n -> n == 1 ? Mono.just(before)
                                : Mono.error(new ResponseStatusException(HttpStatus.CONFLICT,
                                        "'" + key + "' was changed by someone else; reload and try again"))))
                .flatMap(before -> audit.record(admin.id(), "SETTING_CHANGED", "SETTING", key,
                        Map.of("value", String.valueOf(before.value())), Map.of("value", String.valueOf(json.treeToValue(clean, Object.class))), null))
                .then(findOne(key))
                .as(transaction::transactional);
    }

    private Mono<Setting> findOne(String key) {
        return all().filter(s -> s.key().equals(key)).next()
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "No setting '" + key + "'")));
    }

    /** Returns the value to store, or throws if it breaks the rule. Text is trimmed; empty text means null (no banner). */
    static JsonNode check(Rule rule, JsonNode value) {
        if (value == null) {
            throw new IllegalArgumentException();
        }
        switch (rule) {
            case Whole w -> {
                if (!value.isIntegralNumber() || value.asLong() < w.min() || value.asLong() > w.max()) {
                    throw new IllegalArgumentException();
                }
                return value;
            }
            case Flag f -> {
                if (!value.isBoolean()) {
                    throw new IllegalArgumentException();
                }
                return value;
            }
            case Text t -> {
                if (value.isNull()) {
                    return value;
                }
                if (!value.isString()) {
                    throw new IllegalArgumentException();
                }
                String text = value.asString().strip();
                if (text.length() > t.maxLength()) {
                    throw new IllegalArgumentException();
                }
                return text.isEmpty() ? tools.jackson.databind.node.NullNode.getInstance()
                        : tools.jackson.databind.node.JsonNodeFactory.instance.stringNode(text);
            }
        }
    }

    private Setting toSetting(Readable row) {
        String key = row.get("key", String.class);
        Rule rule = RULES.get(key);
        Object value = json.readValue(row.get("value", String.class), Object.class);
        return new Setting(key, value, row.get("description", String.class), rule == null ? "" : rule.describe(), isPublic(key),
                row.get("updated_by", String.class), row.get("updated_by_name", String.class),
                row.get("updated_at", OffsetDateTime.class), row.get("version", Long.class));
    }

    static List<String> keys() {
        return RULES.keySet().stream().sorted().toList();
    }
}
