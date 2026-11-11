package com.shelfwise.core.purchasing;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Invoice records (DL-04): one current file per PO, older versions kept. The file itself lives in object storage. */
@Repository
public class InvoiceRepository {

    public record InvoiceRow(UUID id, String poId, String objectKey, String fileName, String contentType, long sizeBytes,
                             String sha256, boolean current, String uploadedBy, String uploadedByName, OffsetDateTime uploadedAt) {
    }

    private static final String SELECT = """
            SELECT i.id, i.po_id, i.object_key, i.file_name, i.content_type, i.size_bytes, i.sha256, i.is_current,
                   i.uploaded_by, u.name AS uploaded_by_name, i.uploaded_at
            FROM invoices i JOIN users u ON u.id = i.uploaded_by
            """;

    private final DatabaseClient db;

    public InvoiceRepository(DatabaseClient db) {
        this.db = db;
    }

    /** Newest first; the current one is first. */
    public Flux<InvoiceRow> forPo(String poId) {
        return db.sql(SELECT + " WHERE i.po_id = :po ORDER BY i.uploaded_at DESC, i.id")
                .bind("po", poId)
                .map((row, meta) -> map(row))
                .all();
    }

    public Mono<InvoiceRow> find(String poId, UUID id) {
        return db.sql(SELECT + " WHERE i.po_id = :po AND i.id = :id")
                .bind("po", poId).bind("id", id)
                .map((row, meta) -> map(row))
                .one();
    }

    /** Makes any current invoice an old version, then records the new one as current. Run in one transaction. */
    public Mono<Void> replaceCurrent(UUID id, String poId, String objectKey, String fileName, String contentType,
                                     long sizeBytes, String sha256, String uploadedBy) {
        return db.sql("UPDATE invoices SET is_current = false WHERE po_id = :po AND is_current")
                .bind("po", poId).then()
                .then(db.sql("""
                                INSERT INTO invoices (id, po_id, object_key, file_name, content_type, size_bytes, sha256, is_current, uploaded_by)
                                VALUES (:id, :po, :key, :name, :type, :size, :sha, true, :by)
                                """)
                        .bind("id", id).bind("po", poId).bind("key", objectKey).bind("name", fileName)
                        .bind("type", contentType).bind("size", sizeBytes).bind("sha", sha256).bind("by", uploadedBy)
                        .then());
    }

    private static InvoiceRow map(io.r2dbc.spi.Readable row) {
        return new InvoiceRow(row.get("id", UUID.class), row.get("po_id", String.class), row.get("object_key", String.class),
                row.get("file_name", String.class), row.get("content_type", String.class), row.get("size_bytes", Long.class),
                row.get("sha256", String.class), Boolean.TRUE.equals(row.get("is_current", Boolean.class)),
                row.get("uploaded_by", String.class), row.get("uploaded_by_name", String.class),
                row.get("uploaded_at", OffsetDateTime.class));
    }
}
