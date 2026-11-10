package com.shelfwise.core.dataimport;

import java.time.Duration;

import org.springframework.r2dbc.core.DatabaseClient;

/** Shared helpers for the import tests. */
final class ImportTestSupport {

    private ImportTestSupport() {
    }

    static void emptyDatabase(DatabaseClient db) {
        db.sql("""
                TRUNCATE import_issues, raw_import_records, import_runs, po_approvals, po_lines, purchase_orders,
                         order_lines, customer_orders, stock_movements, inventory, products,
                         suppliers, refresh_tokens, users, stores CASCADE
                """).then().block(Duration.ofSeconds(30));
    }

    /** Reads a single numeric value (count, quantity) as a long. */
    static long number(DatabaseClient db, String sql) {
        return db.sql(sql).map((row, meta) -> ((Number) row.get(0)).longValue()).one().block(Duration.ofSeconds(10));
    }
}
