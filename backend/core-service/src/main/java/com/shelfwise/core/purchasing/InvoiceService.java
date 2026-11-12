package com.shelfwise.core.purchasing;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ResponseStatusException;

import com.shelfwise.core.audit.AuditLog;
import com.shelfwise.core.auth.StoreAccess;
import com.shelfwise.core.auth.UserAccount;
import com.shelfwise.core.purchasing.InvoiceFileCheck.Result;
import com.shelfwise.core.purchasing.InvoiceRepository.InvoiceRow;
import com.shelfwise.core.purchasing.PurchaseOrderRepository.PoRow;
import com.shelfwise.core.storage.ObjectStorage;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Supplier invoices on a purchase order (M20). A store manager of the PO's store (or an admin) attaches a
 * PDF, PNG or JPEG up to 20 MB; attaching again REPLACES the current file and keeps the old one as an older
 * version. Finance, admins and the store's managers can view. The file's type is decided from its contents.
 * Order of work: check, save the file to storage, then record it (a failed record removes the stored file).
 */
@Service
public class InvoiceService {

    /** Download of a stored file: its bytes plus what to tell the browser. */
    public record Download(InvoiceRow invoice, byte[] content) {
    }

    private final PurchaseOrderRepository pos;
    private final InvoiceRepository invoices;
    private final ObjectStorage storage;
    private final AuditLog audit;
    private final TransactionalOperator transaction;

    public InvoiceService(PurchaseOrderRepository pos, InvoiceRepository invoices, ObjectStorage storage, AuditLog audit,
                          ReactiveTransactionManager transactionManager) {
        this.pos = pos;
        this.invoices = invoices;
        this.storage = storage;
        this.audit = audit;
        this.transaction = TransactionalOperator.create(transactionManager);
    }

    public Flux<InvoiceRow> list(UserAccount user, String poId) {
        return load(poId).flatMapMany(po -> {
            StoreAccess.readableStore(user, po.storeId());
            return invoices.forPo(poId);
        });
    }

    public Mono<Download> download(UserAccount user, String poId, UUID invoiceId) {
        return load(poId).flatMap(po -> {
            StoreAccess.readableStore(user, po.storeId());
            return invoices.find(poId, invoiceId)
                    .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "No such invoice on " + poId)))
                    .flatMap(inv -> storage.get(inv.objectKey()).map(bytes -> new Download(inv, bytes)));
        });
    }

    /** Attach, or replace the current invoice. */
    public Mono<InvoiceRow> upload(UserAccount user, String poId, FilePart file) {
        return load(poId).flatMap(po -> {
            StoreAccess.checkCanChangeStock(user, po.storeId());
            return readLimited(file).flatMap(bytes -> store(user, po, file.filename(), bytes));
        });
    }

    private Mono<InvoiceRow> store(UserAccount user, PoRow po, String originalName, byte[] bytes) {
        Result check = InvoiceFileCheck.check(bytes);
        if (!check.ok()) {
            return Mono.error(rejection(check));
        }
        UUID id = UUID.randomUUID();
        String key = "invoices/" + po.id() + "/" + id;
        String name = cleanName(originalName, check.contentType());
        return invoices.forPo(po.id()).filter(InvoiceRow::current).next().map(InvoiceRow::id).map(UUID::toString).defaultIfEmpty("")
                .flatMap(previous -> storage.put(key, bytes, check.contentType())
                        .then(invoices.replaceCurrent(id, po.id(), key, name, check.contentType(), bytes.length, sha256(bytes), user.id())
                                .then(audit.record(user.id(), previous.isEmpty() ? "INVOICE_ATTACHED" : "INVOICE_REPLACED", "PURCHASE_ORDER",
                                        po.id(), previous.isEmpty() ? null : Map.of("invoiceId", previous),
                                        Map.of("invoiceId", id.toString(), "fileName", name, "contentType", check.contentType(), "sizeBytes", bytes.length),
                                        null))
                                .as(transaction::transactional)
                                .onErrorResume(e -> storage.delete(key).then(Mono.error(translate(e)))))
                        .then(invoices.find(po.id(), id)));
    }

    /** Reads the upload but stops as soon as it is over 20 MB, so a huge file is never held in memory. */
    private static Mono<byte[]> readLimited(FilePart file) {
        return DataBufferUtils.join(file.content(), InvoiceFileCheck.MAX_BYTES + 1)
                .map(buffer -> {
                    try {
                        byte[] bytes = new byte[buffer.readableByteCount()];
                        buffer.read(bytes);
                        return bytes;
                    } finally {
                        DataBufferUtils.release(buffer);
                    }
                })
                .defaultIfEmpty(new byte[0])
                .onErrorMap(DataBufferLimitException.class, e -> new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE,
                        "The file is over the 20 MB limit"));
    }

    private Mono<PoRow> load(String id) {
        return pos.find(id).switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "No purchase order '" + id + "'")));
    }

    private static Throwable translate(Throwable e) {
        // Two uploads at the same moment: the unique "one current invoice" rule lets only one win.
        if (e instanceof DataIntegrityViolationException) {
            return new ResponseStatusException(HttpStatus.CONFLICT, "Someone else attached an invoice at the same time; try again");
        }
        return e;
    }

    private static ResponseStatusException rejection(Result check) {
        HttpStatus status = switch (check.problem()) {
            case TOO_LARGE -> HttpStatus.CONTENT_TOO_LARGE;
            case UNSUPPORTED_TYPE -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            case EMPTY, CORRUPT -> HttpStatus.UNPROCESSABLE_CONTENT;
        };
        return new ResponseStatusException(status, check.message());
    }

    /** Keeps only the file's own name (no folders) and gives it the right ending for what it really is. */
    static String cleanName(String original, String contentType) {
        String name = original == null ? "" : original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).strip();
        String ext = switch (contentType) {
            case "application/pdf" -> ".pdf";
            case "image/png" -> ".png";
            default -> ".jpg";
        };
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        if (stem.isBlank()) {
            stem = "invoice";
        }
        if (stem.length() > 100) {
            stem = stem.substring(0, 100);
        }
        return stem + ext;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
