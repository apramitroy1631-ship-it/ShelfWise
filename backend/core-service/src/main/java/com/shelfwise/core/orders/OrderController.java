package com.shelfwise.core.orders;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.shelfwise.core.auth.UserAccount;
import com.shelfwise.core.catalogue.CatalogueDtos.Page;
import com.shelfwise.core.orders.OrderDtos.CreateOrderRequest;
import com.shelfwise.core.orders.OrderDtos.OrderDetail;
import com.shelfwise.core.orders.OrderDtos.OrderSummary;
import com.shelfwise.core.orders.OrderDtos.StatusChangeRequest;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Customer orders API (M11). Store scope is checked on the server for every call. */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orders;
    private final OrderExport export;

    public OrderController(OrderService orders, OrderExport export) {
        this.orders = orders;
        this.export = export;
    }

    /** e.g. /api/orders?storeId=S01&status=PENDING&q=Zoya&from=2026-10-01&to=2026-10-31&stuck=true&page=0&size=20 (dates in IST) */
    @GetMapping
    public Mono<Page<OrderSummary>> search(@AuthenticationPrincipal UserAccount user,
                                           @RequestParam(required = false) String storeId,
                                           @RequestParam(required = false) String status,
                                           @RequestParam(required = false) String q,
                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                           @RequestParam(defaultValue = "false") boolean stuck,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        return orders.search(user, storeId, status, q, from, to, stuck, page, size);
    }

    /**
     * The filtered list as a CSV file (M22), same filters as the list, every page. Opens in Excel
     * (UTF-8 with a byte-order mark so names with non-English letters show correctly).
     */
    @GetMapping("/export.csv")
    public ResponseEntity<Flux<String>> export(@AuthenticationPrincipal UserAccount user,
                                               @RequestParam(required = false) String storeId,
                                               @RequestParam(required = false) String status,
                                               @RequestParam(required = false) String q,
                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(OrderExport.fileName(user, storeId, from, to)).build().toString())
                .body(Flux.just("\uFEFF").concatWith(export.csv(user, storeId, status, q, from, to)));
    }

    @GetMapping("/{id}")
    public Mono<OrderDetail> get(@AuthenticationPrincipal UserAccount user, @PathVariable String id) {
        return orders.get(user, id);
    }

    /** Send a unique Idempotency-Key per order attempt; retrying with the same key never creates a second order. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<OrderDetail> create(@AuthenticationPrincipal UserAccount user,
                                    @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
                                    @Validated @RequestBody CreateOrderRequest request) {
        return orders.create(user, request, idempotencyKey);
    }

    @PostMapping("/{id}/status")
    public Mono<OrderDetail> changeStatus(@AuthenticationPrincipal UserAccount user, @PathVariable String id,
                                          @Validated @RequestBody StatusChangeRequest request) {
        return orders.changeStatus(user, id, request.to(), request.version());
    }
}
