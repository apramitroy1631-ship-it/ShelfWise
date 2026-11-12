package com.shelfwise.core.purchasing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;

import com.shelfwise.core.TestDatabaseConfig;
import com.shelfwise.core.storage.ObjectStorage;

import reactor.core.publisher.Mono;

/**
 * Supplier invoices (M20): attach, view and replace a PDF or image up to 20 MB, with the file type decided
 * from its contents. Uses the provided upload samples; the 22 MB trap file is generated here, not committed.
 */
@SpringBootTest
@Import({TestDatabaseConfig.class, InvoicesApiTest.InMemoryStorage.class})
class InvoicesApiTest {

    private static final String PASSWORD = "Test-Only-Pass-1";
    private static final Path SAMPLES = Path.of("../../data/upload_samples");
    private static final byte[] PDF_START = {'%', 'P', 'D', 'F', '-', '1', '.', '4', '\n'};
    private static final byte[] PDF_END = {'\n', '%', '%', 'E', 'O', 'F', '\n'};

    /** Stands in for SeaweedFS / R2 so the test needs no object store. */
    @TestConfiguration(proxyBeanMethods = false)
    static class InMemoryStorage {
        @Bean
        @Primary
        ObjectStorage inMemoryStorage() {
            var files = new ConcurrentHashMap<String, byte[]>();
            return new ObjectStorage() {
                @Override
                public Mono<Void> put(String key, byte[] content, String contentType) {
                    files.put(key, content);
                    return Mono.empty();
                }

                @Override
                public Mono<byte[]> get(String key) {
                    byte[] b = files.get(key);
                    return b == null ? Mono.error(new IllegalStateException("missing " + key)) : Mono.just(b);
                }

                @Override
                public Mono<Void> delete(String key) {
                    files.remove(key);
                    return Mono.empty();
                }
            };
        }
    }

    @Autowired
    private ApplicationContext context;
    @Autowired
    private DatabaseClient db;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private WebTestClient client;
    private String manager;
    private String otherManager;
    private String finance;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToApplicationContext(context).build();
        run("TRUNCATE invoices, po_receipts, po_approvals, po_lines, purchase_orders, outbox_events, audit_log, refresh_tokens, products, suppliers, users, stores CASCADE");
        run("INSERT INTO stores (id, name, city, state, opened_on) VALUES ('S01','Whitefield','Bengaluru','Karnataka','2019-03-15'), ('S02','Madhapur','Hyderabad','Telangana','2020-04-15')");
        db.sql("""
                INSERT INTO users (id, name, email, role, store_id, active, password_hash) VALUES
                  ('U03','Arjun Rao','arjun@test.example','STORE_MANAGER','S01',true,:h),
                  ('U04','Sneha Reddy','sneha@test.example','STORE_MANAGER','S02',true,:h),
                  ('U10','Meera Joshi','meera@test.example','FINANCE_APPROVER',NULL,true,:h)
                """).bind("h", passwordEncoder.encode(PASSWORD)).then().block(Duration.ofSeconds(10));
        run("INSERT INTO suppliers (id, name) VALUES ('SUP23','Supplier SUP23')");
        run("""
            INSERT INTO purchase_orders (id, store_id, supplier_id, created_by, status, total_amount, created_at, source)
            VALUES ('PO-9001','S01','SUP23','U03','APPROVED',300,now(),'APP')
            """);
        manager = login("arjun@test.example");
        otherManager = login("sneha@test.example");
        finance = login("meera@test.example");
    }

    @Test
    void attachesAValidPdfAndAnImage_andFinanceCanView() throws IOException {
        byte[] pdf = Files.readAllBytes(SAMPLES.resolve("invoice_valid.pdf"));

        Map<?, ?> first = upload(manager, "PO-9001", "invoice_valid.pdf", pdf, 201);
        assertThat(first.get("contentType")).isEqualTo("application/pdf");
        assertThat(first.get("current")).isEqualTo(true);
        assertThat(((Number) first.get("sizeBytes")).intValue()).isEqualTo(pdf.length);
        assertThat(first.containsKey("objectKey")).isFalse();

        byte[] shown = client.get().uri("/api/purchase-orders/PO-9001/invoices/" + first.get("id") + "/file")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + finance)
                .exchange().expectStatus().isOk()
                .expectHeader().contentType("application/pdf")
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                .expectBody(byte[].class).returnResult().getResponseBody();
        assertThat(shown).isEqualTo(pdf);
        assertThat(number("SELECT count(*) FROM audit_log WHERE action = 'INVOICE_ATTACHED' AND entity_id = 'PO-9001'")).isEqualTo(1);
    }

    @Test
    void replacingKeepsTheOldVersion_andOnlyOneIsCurrent() throws IOException {
        Map<?, ?> first = upload(manager, "PO-9001", "invoice_valid.pdf", Files.readAllBytes(SAMPLES.resolve("invoice_valid.pdf")), 201);
        byte[] png = Files.readAllBytes(SAMPLES.resolve("invoice_scan.png"));
        Map<?, ?> second = upload(manager, "PO-9001", "scan.png", png, 201);
        assertThat(second.get("contentType")).isEqualTo("image/png");

        List<Map<String, Object>> versions = client.get().uri("/api/purchase-orders/PO-9001/invoices")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + manager)
                .exchange().expectStatus().isOk()
                .expectBody(new org.springframework.core.ParameterizedTypeReference<List<Map<String, Object>>>() { })
                .returnResult().getResponseBody();
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).get("id")).isEqualTo(second.get("id"));
        assertThat(versions.get(0).get("current")).isEqualTo(true);
        assertThat(versions.get(1).get("id")).isEqualTo(first.get("id"));
        assertThat(versions.get(1).get("current")).isEqualTo(false);
        assertThat(number("SELECT count(*) FROM audit_log WHERE action = 'INVOICE_REPLACED'")).isEqualTo(1);

        // the old version can still be opened
        client.get().uri("/api/purchase-orders/PO-9001/invoices/" + first.get("id") + "/file")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + manager).exchange().expectStatus().isOk();
    }

    @Test
    void anExecutableRenamedToPdfIsRefused_byContentNotName() throws IOException {
        Map<?, ?> error = upload(manager, "PO-9001", "invoice_renamed.pdf", Files.readAllBytes(SAMPLES.resolve("invoice_renamed.pdf")), 415);
        assertThat(String.valueOf(error.get("detail"))).contains("contents");
        assertThat(number("SELECT count(*) FROM invoices")).isZero();
    }

    @Test
    void aTruncatedPdfIsRefusedAsCorrupt() throws IOException {
        Map<?, ?> error = upload(manager, "PO-9001", "invoice_truncated.pdf", Files.readAllBytes(SAMPLES.resolve("invoice_truncated.pdf")), 422);
        assertThat(String.valueOf(error.get("detail"))).contains("incomplete");
        assertThat(number("SELECT count(*) FROM invoices")).isZero();
    }

    @Test
    void aFileOver20MbIsRefused_andNothingIsStored() {
        byte[] big = new byte[20 * 1024 * 1024 + 1024 * 1024];                 // a valid-looking PDF just over 20 MB (the 22 MB sample is not committed)
        Arrays.fill(big, (byte) ' ');
        byte[] head = "%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII);
        byte[] tail = "\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(head, 0, big, 0, head.length);
        System.arraycopy(tail, 0, big, big.length - tail.length, tail.length);

        upload(manager, "PO-9001", "invoice_too_large.pdf", big, 413);
        assertThat(number("SELECT count(*) FROM invoices")).isZero();
    }

    @Test
    void aFarOversizeFileLikeThe22MbSampleIsRefusedWith413_notAServerError() {
        byte[] big = new byte[22 * 1024 * 1024];                                 // the size of the dataset's invoice_too_large.pdf
        Arrays.fill(big, (byte) ' ');
        byte[] head = PDF_START;
        byte[] tail = PDF_END;
        System.arraycopy(head, 0, big, 0, head.length);
        System.arraycopy(tail, 0, big, big.length - tail.length, tail.length);

        Map<?, ?> error = upload(manager, "PO-9001", "invoice_too_large.pdf", big, 413);
        assertThat(String.valueOf(error.get("detail"))).contains("20 MB");
        assertThat(number("SELECT count(*) FROM invoices")).isZero();
    }

    @Test
    void aFileExactlyAtTheLimitIsAccepted() {
        byte[] max = new byte[InvoiceFileCheck.MAX_BYTES];
        Arrays.fill(max, (byte) ' ');
        byte[] head = "%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII);
        byte[] tail = "\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(head, 0, max, 0, head.length);
        System.arraycopy(tail, 0, max, max.length - tail.length, tail.length);

        upload(manager, "PO-9001", "big.pdf", max, 201);
    }

    @Test
    void anEmptyFileIsRefused() {
        upload(manager, "PO-9001", "empty.pdf", new byte[0], 422);
    }

    @Test
    void folderPartsAndWrongEndingsInTheNameAreCleaned() throws IOException {
        Map<?, ?> v = upload(manager, "PO-9001", "..\\..\\evil/inv.exe", Files.readAllBytes(SAMPLES.resolve("invoice_valid.pdf")), 201);
        assertThat(v.get("fileName")).isEqualTo("inv.pdf");
    }

    @Test
    void onlyThatStoresManagersCanAttach_financeCanOnlyView() throws IOException {
        byte[] pdf = Files.readAllBytes(SAMPLES.resolve("invoice_valid.pdf"));
        upload(otherManager, "PO-9001", "x.pdf", pdf, 403);                      // another store
        upload(finance, "PO-9001", "x.pdf", pdf, 403);                           // finance doesn't change POs' files
        upload(manager, "PO-0000", "x.pdf", pdf, 404);

        client.get().uri("/api/purchase-orders/PO-9001/invoices").header(HttpHeaders.AUTHORIZATION, "Bearer " + otherManager)
                .exchange().expectStatus().isForbidden();
        client.get().uri("/api/purchase-orders/PO-9001/invoices").exchange().expectStatus().isUnauthorized();
    }

    // --- helpers ---------------------------------------------------------------

    private Map<?, ?> upload(String token, String po, String filename, byte[] content, int expectedStatus) {
        var parts = new MultipartBodyBuilder();
        parts.part("file", new org.springframework.core.io.ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        }).contentType(MediaType.APPLICATION_OCTET_STREAM);                       // the claimed type is ignored: contents decide
        return client.mutate().responseTimeout(Duration.ofSeconds(60)).build()
                .post().uri("/api/purchase-orders/" + po + "/invoices")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .body(BodyInserters.fromMultipartData(parts.build()))
                .exchange().expectStatus().isEqualTo(expectedStatus)
                .expectBody(Map.class).returnResult().getResponseBody();
    }

    private String login(String email) {
        return client.post().uri("/api/auth/login").bodyValue(Map.of("email", email, "password", PASSWORD))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody().get("accessToken").toString();
    }

    private long number(String sql) {
        return db.sql(sql).map((row, meta) -> ((Number) row.get(0)).longValue()).one().block(Duration.ofSeconds(10));
    }

    private void run(String sql) {
        db.sql(sql).then().block(Duration.ofSeconds(10));
    }
}
