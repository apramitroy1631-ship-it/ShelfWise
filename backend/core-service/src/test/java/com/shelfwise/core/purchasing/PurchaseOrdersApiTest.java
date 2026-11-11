package com.shelfwise.core.purchasing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.shelfwise.core.TestDatabaseConfig;
import com.shelfwise.core.auth.UserAccount;
import com.shelfwise.core.purchasing.PurchaseOrderDtos.DecisionRequest;
import com.shelfwise.core.purchasing.PurchaseOrderDtos.LineRequest;
import com.shelfwise.core.purchasing.PurchaseOrderDtos.ReceiveRequest;

import reactor.core.publisher.Flux;

/**
 * Purchase orders end to end (M19): draft, submit, finance decision with the second-approver rule,
 * re-approval of flagged imported POs (D17-D19), receiving in parts (D21), closing stuck POs (D22),
 * outbox events, and two approvers clicking at the same moment (D31).
 */
@SpringBootTest
@Import(TestDatabaseConfig.class)
class PurchaseOrdersApiTest {

    private static final String PASSWORD = "Test-Only-Pass-1";
    private static final String PO = "/api/purchase-orders";

    @Autowired
    private ApplicationContext context;
    @Autowired
    private DatabaseClient db;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private PurchaseOrderService service;

    private WebTestClient client;
    private String manager;
    private String otherManager;
    private String admin;
    private String meera;
    private String anil;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToApplicationContext(context).build();
        run("""
            TRUNCATE po_receipts, po_approvals, po_lines, purchase_orders, outbox_events, audit_log, stock_movements, inventory,
                     products, suppliers, refresh_tokens, users, stores CASCADE
            """);
        // TRUNCATE users CASCADE also empties app_settings (it references users), so put the rule back
        run("""
            INSERT INTO app_settings (key, value, description) VALUES ('purchaseOrders.secondApprovalAboveInr', '50000', 'test')
            ON CONFLICT (key) DO UPDATE SET value = '50000'
            """);
        run("INSERT INTO stores (id, name, city, state, opened_on) VALUES ('S01','Whitefield','Bengaluru','Karnataka','2019-03-15'), ('S02','Madhapur','Hyderabad','Telangana','2020-04-15')");
        db.sql("""
                INSERT INTO users (id, name, email, role, store_id, active, password_hash) VALUES
                  ('U01','Rohit Menon','rohit@test.example','ADMIN',NULL,true,:h),
                  ('U03','Arjun Rao','arjun@test.example','STORE_MANAGER','S01',true,:h),
                  ('U04','Sneha Reddy','sneha@test.example','STORE_MANAGER','S02',true,:h),
                  ('U10','Meera Joshi','meera@test.example','FINANCE_APPROVER',NULL,true,:h),
                  ('U11','Anil Gupta','anil@test.example','FINANCE_APPROVER',NULL,true,:h)
                """).bind("h", passwordEncoder.encode(PASSWORD)).then().block(Duration.ofSeconds(10));
        run("INSERT INTO suppliers (id, name) VALUES ('SUP23','Supplier SUP23'), ('SUP05','Supplier SUP05')");
        run("""
            INSERT INTO products (sku, name, category, price_inr, cost_price_inr, gst_rate, supplier_id, status, reorder_level, catalogued_on, source) VALUES
              ('SW-SNK-00091','Smart Buy Potato Chips 150g','Snacks',25,20,12,'SUP23','ACTIVE',25,'2025-12-02','IMPORT'),
              ('SW-GRO-00055','Home Basics Iodised Salt 1kg','Grocery',31,25,0,'SUP23','ACTIVE',10,'2025-01-01','IMPORT'),
              ('SW-ELE-00012','Volt Mixer Grinder 750W','Electronics',3499,1000,18,'SUP23','ACTIVE',5,'2025-01-01','IMPORT'),
              ('SW-SNK-00064','Fresh Basket Chocolate Bar 100g','Snacks',52,42,12,'SUP23','ARCHIVED',25,'2025-04-20','IMPORT'),
              ('SW-HOM-00033','Home Basics Steel Bottle 1L','Home',399,250,18,'SUP05','ACTIVE',10,'2025-01-01','IMPORT')
            """);
        run("""
            INSERT INTO inventory (store_id, sku, quantity, reorder_level) VALUES
              ('S01','SW-SNK-00091',37,25), ('S01','SW-GRO-00055',3,10), ('S01','SW-SNK-00064',2,25)
            """);
        manager = login("arjun@test.example");
        otherManager = login("sneha@test.example");
        admin = login("rohit@test.example");
        meera = login("meera@test.example");
        anil = login("anil@test.example");
    }

    // --- the whole flow --------------------------------------------------------

    @Test
    void draftSubmitApproveAndReceiveInParts_D21() {
        Map<?, ?> po = create(manager, "SUP23", List.of(line("SW-SNK-00091", 10), line("SW-GRO-00055", 4)), 201);
        String id = id(po);
        assertThat(field(po, "status")).isEqualTo("DRAFT");
        assertThat(((Number) field(po, "totalAmount")).intValue()).isEqualTo(10 * 20 + 4 * 25);   // catalogue cost price
        assertThat(id).startsWith("PO-1");

        po = post(manager, PO + "/" + id + "/submit", Map.of("version", version(po)), 200);
        assertThat(field(po, "status")).isEqualTo("SUBMITTED");

        po = decide(meera, id, "APPROVED", null, version(po), 200);
        assertThat(field(po, "status")).isEqualTo("APPROVED");                 // ₹300: one approval is enough

        po = receive(manager, id, List.of(line("SW-SNK-00091", 4)), version(po), 200);
        assertThat(field(po, "status")).isEqualTo("PARTIALLY_RECEIVED");
        assertThat(stock("S01", "SW-SNK-00091")).isEqualTo(41);

        po = receive(manager, id, List.of(line("SW-SNK-00091", 6), line("SW-GRO-00055", 4)), version(po), 200);
        assertThat(field(po, "status")).isEqualTo("RECEIVED");
        assertThat(stock("S01", "SW-SNK-00091")).isEqualTo(47);
        assertThat(stock("S01", "SW-GRO-00055")).isEqualTo(7);
        assertThat(number("SELECT count(*) FROM stock_movements WHERE reason = 'PO_RECEIPT' AND reference_id = '" + id + "'")).isEqualTo(3);
        assertThat(number("SELECT count(*) FROM po_receipts WHERE po_id = '" + id + "'")).isEqualTo(3);

        receive(manager, id, List.of(line("SW-SNK-00091", 1)), version(po), 409);   // nothing left to receive
        assertThat(eventTypes(id)).containsExactly(
                "PurchaseOrderSubmitted", "PurchaseOrderDecided", "PurchaseOrderReceived", "PurchaseOrderReceived");
    }

    // --- approvals ---------------------------------------------------------------

    @Test
    void aboveTheLimitTwoDifferentApproversAreNeeded() {
        String id = submitted(manager, List.of(line("SW-ELE-00012", 60)));        // 60 x ₹1,000 = ₹60,000

        Map<?, ?> po = decide(meera, id, "APPROVED", null, currentVersion(id), 200);
        assertThat(field(po, "status")).isEqualTo("SUBMITTED");                 // waiting for the second approver
        assertThat(field(po, "validApprovals")).isEqualTo(1);
        assertThat(field(po, "approvalsNeeded")).isEqualTo(2);

        decide(meera, id, "APPROVED", null, version(po), 409);                    // same person twice (D31)

        po = decide(anil, id, "APPROVED", null, version(po), 200);
        assertThat(field(po, "status")).isEqualTo("APPROVED");
        assertThat(field(po, "validApprovals")).isEqualTo(2);
        assertThat(((List<?>) po.get("approvals"))).hasSize(2);
        // N6: screens show people's names; the IDs stay alongside
        assertThat(po.get("createdBy")).isEqualTo("U03");
        assertThat(po.get("createdByName")).isEqualTo("Arjun Rao");
        List<String> approverNames = ((List<?>) po.get("approvals")).stream().map(a -> (String) ((Map<?, ?>) a).get("approverName")).toList();
        assertThat(approverNames).containsExactly("Meera Joshi", "Anil Gupta");
    }

    /** Remote config (DL-06): raising the limit changes the rule on the next request, with no redeploy. */
    @Test
    void theSecondApprovalLimitComesFromRemoteConfig() {
        String id = submitted(manager, List.of(line("SW-ELE-00012", 60)));
        run("UPDATE app_settings SET value = '100000' WHERE key = 'purchaseOrders.secondApprovalAboveInr'");

        Map<?, ?> po = decide(meera, id, "APPROVED", null, currentVersion(id), 200);
        assertThat(field(po, "status")).isEqualTo("APPROVED");
        assertThat(field(po, "approvalsNeeded")).isEqualTo(1);
    }

    @Test
    void aRejectionNeedsAReasonAndIsAudited() {
        String id = submitted(manager, List.of(line("SW-SNK-00091", 10)));

        decide(meera, id, "REJECTED", "  ", currentVersion(id), 400);
        Map<?, ?> po = decide(meera, id, "REJECTED", "Supplier price too high", currentVersion(id), 200);

        assertThat(field(po, "status")).isEqualTo("REJECTED");
        assertThat(number("SELECT count(*) FROM audit_log WHERE action = 'PO_REJECTED' AND reason = 'Supplier price too high'")).isEqualTo(1);
        decide(anil, id, "APPROVED", null, version(po), 409);                     // nothing left to decide
        receive(manager, id, List.of(line("SW-SNK-00091", 1)), version(po), 409);
    }

    @Test
    void onlyFinanceDecidesAndNeverOnTheirOwnPo_D19() {
        String id = submitted(manager, List.of(line("SW-SNK-00091", 10)));
        decide(manager, id, "APPROVED", null, currentVersion(id), 403);
        decide(admin, id, "APPROVED", null, currentVersion(id), 403);

        // an imported PO raised by a finance user (D19): that person can't approve it
        importedPo("PO-0900", "U10", "SUBMITTED", null, false, 10, 0);
        decide(meera, "PO-0900", "APPROVED", null, currentVersion("PO-0900"), 403);
        decide(anil, "PO-0900", "APPROVED", null, currentVersion("PO-0900"), 200);
    }

    /** Two finance approvers click "Approve" at the same moment on the same version: exactly one wins (D31). */
    @Test
    void twoApproversAtTheSameMomentCountOnce_D31() {
        String id = submitted(manager, List.of(line("SW-ELE-00012", 60)));
        long version = currentVersion(id);
        UserAccount meeraUser = new UserAccount("U10", "Meera Joshi", "meera@test.example", UserAccount.Role.FINANCE_APPROVER, null, true, null);
        UserAccount anilUser = new UserAccount("U11", "Anil Gupta", "anil@test.example", UserAccount.Role.FINANCE_APPROVER, null, true, null);

        List<String> outcomes = Flux.just(meeraUser, anilUser, meeraUser, anilUser)
                .flatMap(u -> service.decide(u, id, new DecisionRequest("APPROVED", null, version))
                        .map(d -> "ok").onErrorReturn("refused"), 4)
                .collectList().block(Duration.ofSeconds(30));

        assertThat(outcomes).filteredOn("ok"::equals).hasSize(1);
        assertThat(number("SELECT count(*) FROM po_approvals WHERE po_id = '" + id + "'")).isEqualTo(1);
        assertThat(field(get(manager, id), "status")).isEqualTo("SUBMITTED");     // still needs the 2nd approver
        assertThat(eventTypes(id)).filteredOn("PurchaseOrderDecided"::equals).hasSize(1);
    }

    /** Two deliveries recorded at the same moment on the same version: only one goes in, stock moves once. */
    @Test
    void twoReceiptsAtTheSameMomentCountOnce() {
        String id = submitted(manager, List.of(line("SW-SNK-00091", 10)));
        decide(meera, id, "APPROVED", null, currentVersion(id), 200);
        long version = currentVersion(id);
        UserAccount arjun = new UserAccount("U03", "Arjun Rao", "arjun@test.example", UserAccount.Role.STORE_MANAGER, "S01", true, null);

        List<String> outcomes = Flux.range(0, 3)
                .flatMap(i -> service.receive(arjun, id, new ReceiveRequest(List.of(new LineRequest("SW-SNK-00091", 5)), version))
                        .map(d -> "ok").onErrorReturn("refused"), 3)
                .collectList().block(Duration.ofSeconds(30));

        assertThat(outcomes).filteredOn("ok"::equals).hasSize(1);
        assertThat(stock("S01", "SW-SNK-00091")).isEqualTo(42);
    }

    // --- flagged imported POs (D17-D19) ----------------------------------------------

    @Test
    void aFlaggedPoMustBeReapprovedBeforeGoodsArrive_D17_D19() {
        importedPo("PO-0116", "U03", "APPROVED", "SELF_APPROVAL", true, 10, 0);
        run("INSERT INTO po_approvals (po_id, approver_id, decision, decided_at, counts, step) VALUES ('PO-0116','U03','APPROVED',now(),false,NULL)");

        receive(manager, "PO-0116", List.of(line("SW-SNK-00091", 5)), currentVersion("PO-0116"), 409);

        Map<?, ?> po = decide(meera, "PO-0116", "APPROVED", null, currentVersion("PO-0116"), 200);
        assertThat(field(po, "requiresReapproval")).isEqualTo(false);
        assertThat(field(po, "status")).isEqualTo("APPROVED");
        assertThat(field(po, "complianceFlag")).isEqualTo("SELF_APPROVAL");      // the history stays visible

        po = receive(manager, "PO-0116", List.of(line("SW-SNK-00091", 5)), version(po), 200);
        assertThat(field(po, "status")).isEqualTo("PARTIALLY_RECEIVED");
    }

    @Test
    void rejectingTheReapprovalOfAPartlyReceivedPoClosesIt() {
        importedPo("PO-0135", "U03", "PARTIALLY_RECEIVED", "SELF_APPROVAL", true, 10, 4);

        Map<?, ?> po = decide(meera, "PO-0135", "REJECTED", "Self-approved; stop the rest", currentVersion("PO-0135"), 200);

        assertThat(field(po, "status")).isEqualTo("CLOSED");
        assertThat(po.get("closedReason").toString()).contains("Self-approved");
        assertThat(((Map<?, ?>) ((List<?>) po.get("lines")).get(0)).get("qtyReceived")).isEqualTo(4);   // received goods stay
    }

    // --- receiving rules ---------------------------------------------------------

    @Test
    void cantReceiveMoreThanOrderedOrAProductNotOnThePo() {
        String id = submitted(manager, List.of(line("SW-SNK-00091", 10)));
        decide(meera, id, "APPROVED", null, currentVersion(id), 200);

        receive(manager, id, List.of(line("SW-SNK-00091", 11)), currentVersion(id), 409);
        receive(manager, id, List.of(line("SW-GRO-00055", 1)), currentVersion(id), 400);
        receive(manager, id, List.of(line("SW-SNK-00091", 6), line("SW-SNK-00091", 5)), currentVersion(id), 409);   // 11 in total

        assertThat(stock("S01", "SW-SNK-00091")).isEqualTo(37);                  // nothing moved
        assertThat(number("SELECT count(*) FROM po_receipts")).isZero();
    }

    @Test
    void receivingAProductNeverStockedInTheStoreCreatesItsStockRow() {
        String id = submitted(otherManager, List.of(line("SW-SNK-00091", 10)));
        decide(meera, id, "APPROVED", null, currentVersion(id), 200);

        receive(otherManager, id, List.of(line("SW-SNK-00091", 10)), currentVersion(id), 200);

        assertThat(stock("S02", "SW-SNK-00091")).isEqualTo(10);
        assertThat(number("SELECT reorder_level FROM inventory WHERE store_id = 'S02' AND sku = 'SW-SNK-00091'")).isEqualTo(25);
    }

    // --- stuck POs (D22) -------------------------------------------------------------

    @Test
    void aStuckPoCanBeClosedWithAReason_D22() {
        importedPo("PO-0200", "U03", "APPROVED", null, false, 10, 0);
        run("UPDATE purchase_orders SET created_at = now() - interval '40 days' WHERE id = 'PO-0200'");
        run("INSERT INTO po_approvals (po_id, approver_id, decision, decided_at, counts, step) VALUES ('PO-0200','U10','APPROVED',now() - interval '40 days',true,1)");
        String fresh = submitted(manager, List.of(line("SW-SNK-00091", 10)));
        decide(meera, fresh, "APPROVED", null, currentVersion(fresh), 200);

        assertThat(field(get(manager, "PO-0200"), "stuck")).isEqualTo(true);
        close(manager, fresh, "Not needed", currentVersion(fresh), 409);          // approved today: not stuck
        close(otherManager, "PO-0200", "Supplier gone", currentVersion("PO-0200"), 403);

        Map<?, ?> po = close(manager, "PO-0200", "Supplier stopped delivering", currentVersion("PO-0200"), 200);
        assertThat(field(po, "status")).isEqualTo("CLOSED");
        assertThat(number("SELECT count(*) FROM audit_log WHERE action = 'PO_CLOSED' AND entity_id = 'PO-0200'")).isEqualTo(1);
    }

    // --- drafting rules and access ---------------------------------------------------

    @Test
    void aDraftIsForOneSupplierAndActiveKnownProducts() {
        create(manager, "SUP23", List.of(line("SW-SNK-00091", 5), line("SW-HOM-00033", 5)), 400);   // SUP05 product
        create(manager, "SUP23", List.of(line("SW-SNK-00064", 5)), 409);                             // archived (D3)
        create(manager, "SUP23", List.of(line("SW-ZZZ-00001", 5)), 400);                             // unknown
        create(manager, "SUP99", List.of(line("SW-SNK-00091", 5)), 400);                             // unknown supplier
        create(manager, "SUP23", List.of(line("SW-SNK-00091", 0)), 400);                             // at least 1
        assertThat(number("SELECT count(*) FROM purchase_orders")).isZero();

        Map<?, ?> po = create(manager, "SUP23", List.of(line("SW-SNK-00091", 5), line("SW-SNK-00091", 3)), 201);
        assertThat(((List<?>) po.get("lines"))).hasSize(1);                        // D14: merged into one line of 8
    }

    @Test
    void storeScopeAndRolesAreCheckedOnTheServer() {
        create(admin, "SUP23", List.of(line("SW-SNK-00091", 5)), 403);
        create(meera, "SUP23", List.of(line("SW-SNK-00091", 5)), 403);
        Map<?, ?> po = create(manager, "SUP23", List.of(line("SW-SNK-00091", 5)), 201);

        client.get().uri(PO + "/" + id(po)).headers(h -> h.setBearerAuth(otherManager)).exchange().expectStatus().isForbidden();
        post(otherManager, PO + "/" + id(po) + "/submit", Map.of("version", version(po)), 403);
        client.get().uri(PO + "?storeId=S01").headers(h -> h.setBearerAuth(otherManager)).exchange().expectStatus().isForbidden();
        client.get().uri(PO).headers(h -> h.setBearerAuth(meera)).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.totalItems").isEqualTo(1);
        client.get().uri(PO).headers(h -> h.setBearerAuth(otherManager)).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.totalItems").isEqualTo(0);
    }

    @Test
    void anExpiredDraftCantBeSubmitted_D22() {
        Map<?, ?> po = create(manager, "SUP23", List.of(line("SW-SNK-00091", 5)), 201);
        run("UPDATE purchase_orders SET status = 'EXPIRED' WHERE id = '" + id(po) + "'");
        post(manager, PO + "/" + id(po) + "/submit", Map.of("version", version(po)), 409);
    }

    @Test
    void lowStockSuggestionsSkipArchivedProducts() {
        client.get().uri(PO + "/suggestions").headers(h -> h.setBearerAuth(manager)).exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1)                                // chips (37 > 25) and archived bars left out
                .jsonPath("$[0].sku").isEqualTo("SW-GRO-00055")
                .jsonPath("$[0].suggestedQty").isEqualTo(17);                       // back up to twice the reorder level
        client.get().uri(PO + "/suggestions").headers(h -> h.setBearerAuth(admin)).exchange().expectStatus().isBadRequest();
    }

    // --- helpers -----------------------------------------------------------------

    private static Map<String, Object> line(String sku, int qty) {
        return Map.of("sku", sku, "qty", qty);
    }

    private Map<?, ?> create(String token, String supplierId, List<Map<String, Object>> lines, int status) {
        return post(token, PO, Map.of("supplierId", supplierId, "lines", lines), status);
    }

    private String submitted(String token, List<Map<String, Object>> lines) {
        Map<?, ?> po = create(token, "SUP23", lines, 201);
        post(token, PO + "/" + id(po) + "/submit", Map.of("version", version(po)), 200);
        return id(po);
    }

    private Map<?, ?> decide(String token, String id, String decision, String reason, long version, int status) {
        Map<String, Object> body = reason == null ? Map.of("decision", decision, "version", version)
                : Map.of("decision", decision, "reason", reason, "version", version);
        return post(token, PO + "/" + id + "/decision", body, status);
    }

    private Map<?, ?> receive(String token, String id, List<Map<String, Object>> lines, long version, int status) {
        return post(token, PO + "/" + id + "/receive", Map.of("lines", lines, "version", version), status);
    }

    private Map<?, ?> close(String token, String id, String reason, long version, int status) {
        return post(token, PO + "/" + id + "/close", Map.of("reason", reason, "version", version), status);
    }

    private Map<?, ?> post(String token, String uri, Object body, int status) {
        return client.post().uri(uri).headers(h -> h.setBearerAuth(token)).bodyValue(body)
                .exchange().expectStatus().isEqualTo(status)
                .expectBody(Map.class).returnResult().getResponseBody();
    }

    private Map<?, ?> get(String token, String id) {
        return client.get().uri(PO + "/" + id).headers(h -> h.setBearerAuth(token))
                .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
    }

    /** An imported PO of one line of chips (₹20 each) in S01, as the importer would have stored it. */
    private void importedPo(String id, String createdBy, String status, String flag, boolean reapproval, int ordered, int received) {
        run("INSERT INTO purchase_orders (id, store_id, supplier_id, created_by, status, total_amount, created_at, source, compliance_flag, requires_reapproval) "
                + "VALUES ('" + id + "','S01','SUP23','" + createdBy + "','" + status + "'," + (ordered * 20) + ",now(),'IMPORT',"
                + (flag == null ? "NULL" : "'" + flag + "'") + "," + reapproval + ")");
        run("INSERT INTO po_lines (po_id, sku, qty_ordered, unit_cost, qty_received) VALUES ('" + id + "','SW-SNK-00091'," + ordered + ",20," + received + ")");
    }

    private static String id(Map<?, ?> detail) {
        return field(detail, "id").toString();
    }

    private static Object field(Map<?, ?> detail, String name) {
        return ((Map<?, ?>) detail.get("summary")).get(name);
    }

    private static long version(Map<?, ?> detail) {
        return ((Number) field(detail, "version")).longValue();
    }

    private long currentVersion(String id) {
        return number("SELECT version FROM purchase_orders WHERE id = '" + id + "'");
    }

    private List<String> eventTypes(String id) {
        return db.sql("SELECT event_type FROM outbox_events WHERE aggregate_id = :id ORDER BY created_at, event_type DESC")
                .bind("id", id).map((row, meta) -> row.get(0, String.class)).all().collectList().block(Duration.ofSeconds(10));
    }

    private String login(String email) {
        return client.post().uri("/api/auth/login").bodyValue(Map.of("email", email, "password", PASSWORD))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody().get("accessToken").toString();
    }

    private long stock(String store, String sku) {
        return number("SELECT quantity FROM inventory WHERE store_id = '" + store + "' AND sku = '" + sku + "'");
    }

    private long number(String sql) {
        return db.sql(sql).map((row, meta) -> ((Number) row.get(0)).longValue()).one().block(Duration.ofSeconds(10));
    }

    private void run(String sql) {
        db.sql(sql).then().block(Duration.ofSeconds(10));
    }
}
