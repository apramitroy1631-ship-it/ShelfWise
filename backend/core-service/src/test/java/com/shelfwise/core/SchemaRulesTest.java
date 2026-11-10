package com.shelfwise.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.r2dbc.core.DatabaseClient;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Proves the rules the database enforces on its own (docs/data-model.md),
 * so they hold even if application code has a bug.
 */
@SpringBootTest
@Import(TestDatabaseConfig.class)
class SchemaRulesTest {

    @Autowired
    private DatabaseClient db;

    @BeforeEach
    void freshData() {
        run("""
            TRUNCATE po_approvals, purchase_orders, stock_movements, inventory, products,
                     suppliers, refresh_tokens, users, stores CASCADE
            """);
        run("INSERT INTO stores (id, name, city, state, opened_on) VALUES ('S01','Whitefield','Bengaluru','Karnataka','2019-03-15')");
        run("""
            INSERT INTO users (id, name, email, role, store_id) VALUES
              ('U03','Arjun Rao','arjun@test.example','STORE_MANAGER','S01'),
              ('U10','Meera Joshi','meera@test.example','FINANCE_APPROVER',NULL),
              ('U11','Anil Gupta','anil@test.example','FINANCE_APPROVER',NULL)
            """);
        run("INSERT INTO suppliers (id, name) VALUES ('SUP15','Supplier SUP15')");
        run("""
            INSERT INTO products (sku, name, category, price_inr, cost_price_inr, gst_rate, supplier_id,
                                  status, reorder_level, catalogued_on, source)
            VALUES ('SW-HOM-00033','Everyday Dishwash Liquid 250ml','Household',96,75,18,'SUP15','ACTIVE',8,'2025-01-01','IMPORT'),
                   ('sw-hom-00033','Everyday Dishwash Liquid 250ml','Household',99,75,18,'SUP15','ACTIVE',8,'2025-01-01','IMPORT')
            """);
        run("INSERT INTO inventory (store_id, sku, quantity, reorder_level) VALUES ('S01','SW-HOM-00033',5,8)");
        run("""
            INSERT INTO purchase_orders (id, store_id, supplier_id, created_by, status, total_amount, created_at, source)
            VALUES ('PO-9001','S01','SUP15','U03','SUBMITTED',60000,now(),'APP')
            """);
    }

    @Test
    void caseDifferentSkusAreSeparateProducts_D1() {
        StepVerifier.create(db.sql("SELECT count(*) FROM products WHERE lower(sku) = 'sw-hom-00033'")
                        .map(r -> r.get(0, Long.class)).one())
                .expectNext(2L)
                .verifyComplete();
    }

    @Test
    void stockCannotGoBelowZero_D5_D29() {
        expectRejected("UPDATE inventory SET quantity = quantity - 6 WHERE store_id = 'S01' AND sku = 'SW-HOM-00033'");
    }

    @Test
    void sameApproverCannotApproveTwice_D31() {
        approve("U10", 1);
        expectRejected(approvalSql("U10", 2));
    }

    @Test
    void onlyOneValidApprovalPerStep_D31() {
        approve("U10", 1);
        expectRejected(approvalSql("U11", 1));   // a second "1st approval" (two clicks at the same moment)
        approve("U11", 2);                       // a different approver as the 2nd step is fine
    }

    @Test
    void productCreatedInAppNeedsAPrice_D2() {
        expectRejected("""
            INSERT INTO products (sku, name, category, price_inr, cost_price_inr, gst_rate, supplier_id,
                                  status, reorder_level, catalogued_on, source)
            VALUES ('SW-NEW-00001','New item','Snacks',NULL,10,5,'SUP15','ACTIVE',5,'2026-11-10','APP')
            """);
    }

    @Test
    void storeManagerMustBelongToAStore_D26() {
        expectRejected("INSERT INTO users (id, name, email, role, store_id) VALUES ('U98','X','x@test.example','STORE_MANAGER',NULL)");
    }

    @Test
    void emailsAreUniqueIgnoringCase() {
        expectRejected("INSERT INTO users (id, name, email, role, store_id) VALUES ('U99','X','ARJUN@test.example','ADMIN',NULL)");
    }

    /**
     * PIP objective 2: overlapping changes must not oversell.
     * Ten requests each try to take 1 unit of a product that has 5, all at the same time.
     * Exactly 5 must succeed and stock must end at 0, never negative.
     */
    @Test
    void concurrentTakesNeverOversell_D29() {
        Flux<Long> tenConcurrentTakes = Flux.range(0, 10)
                .flatMap(i -> db.sql("""
                                UPDATE inventory SET quantity = quantity - 1
                                WHERE store_id = 'S01' AND sku = 'SW-HOM-00033' AND quantity >= 1
                                """)
                        .fetch().rowsUpdated(), 10);

        Long succeeded = tenConcurrentTakes.reduce(0L, Long::sum).block(Duration.ofSeconds(30));
        Integer left = db.sql("SELECT quantity FROM inventory WHERE store_id = 'S01' AND sku = 'SW-HOM-00033'")
                .map(r -> r.get(0, Integer.class)).one().block(Duration.ofSeconds(10));

        assertThat(succeeded).isEqualTo(5L);
        assertThat(left).isZero();
    }

    // --- helpers ---------------------------------------------------------

    private void run(String sql) {
        db.sql(sql).then().block(Duration.ofSeconds(10));
    }

    private static String approvalSql(String approver, int step) {
        return "INSERT INTO po_approvals (po_id, approver_id, decision, decided_at, counts, step) "
                + "VALUES ('PO-9001','" + approver + "','APPROVED',now(),true," + step + ")";
    }

    private void approve(String approver, int step) {
        run(approvalSql(approver, step));
    }

    private void expectRejected(String sql) {
        StepVerifier.create(db.sql(sql).then().then(Mono.just("accepted")))
                .expectError(DataIntegrityViolationException.class)
                .verify(Duration.ofSeconds(10));
    }
}
