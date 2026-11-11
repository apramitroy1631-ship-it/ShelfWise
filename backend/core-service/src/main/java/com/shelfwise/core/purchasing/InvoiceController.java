package com.shelfwise.core.purchasing;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.shelfwise.core.auth.UserAccount;
import com.shelfwise.core.purchasing.InvoiceRepository.InvoiceRow;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Supplier invoices on a purchase order (M20): list versions, attach or replace, download. */
@RestController
@RequestMapping("/api/purchase-orders/{poId}/invoices")
public class InvoiceController {

    /** What the apps see about an invoice file. The storage location is never exposed. */
    public record InvoiceView(UUID id, String fileName, String contentType, long sizeBytes, boolean current,
                              String uploadedBy, String uploadedByName, java.time.OffsetDateTime uploadedAt) {
        static InvoiceView of(InvoiceRow r) {
            return new InvoiceView(r.id(), r.fileName(), r.contentType(), r.sizeBytes(), r.current(), r.uploadedBy(),
                    r.uploadedByName(), r.uploadedAt());
        }
    }

    private final InvoiceService service;

    public InvoiceController(InvoiceService service) {
        this.service = service;
    }

    /** Newest first; the current file is the first one. */
    @GetMapping
    public Flux<InvoiceView> list(@AuthenticationPrincipal UserAccount user, @PathVariable String poId) {
        return service.list(user, poId).map(InvoiceView::of);
    }

    /** Multipart form with one part named "file". Attaching again replaces the current invoice. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<InvoiceView> upload(@AuthenticationPrincipal UserAccount user, @PathVariable String poId,
                                    @RequestPart("file") FilePart file) {
        return service.upload(user, poId, file).map(InvoiceView::of);
    }

    @GetMapping("/{invoiceId}/file")
    public Mono<ResponseEntity<byte[]>> download(@AuthenticationPrincipal UserAccount user, @PathVariable String poId,
                                                 @PathVariable UUID invoiceId) {
        return service.download(user, poId, invoiceId).map(d -> ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(d.invoice().contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(d.invoice().fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(d.content()));
    }
}
