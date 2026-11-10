package com.shelfwise.core.purchasing;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.shelfwise.core.auth.UserAccount;
import com.shelfwise.core.catalogue.CatalogueDtos.Page;
import com.shelfwise.core.purchasing.PurchaseOrderDtos.CloseRequest;
import com.shelfwise.core.purchasing.PurchaseOrderDtos.CreateRequest;
import com.shelfwise.core.purchasing.PurchaseOrderDtos.DecisionRequest;
import com.shelfwise.core.purchasing.PurchaseOrderDtos.Detail;
import com.shelfwise.core.purchasing.PurchaseOrderDtos.ReceiveRequest;
import com.shelfwise.core.purchasing.PurchaseOrderDtos.Suggestion;
import com.shelfwise.core.purchasing.PurchaseOrderDtos.Summary;
import com.shelfwise.core.purchasing.PurchaseOrderDtos.VersionRequest;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Purchase-orders API (M19). Roles and store scope are checked in the service for every call. */
@RestController
@RequestMapping("/api/purchase-orders")
public class PurchaseOrderController {

    private final PurchaseOrderService service;

    public PurchaseOrderController(PurchaseOrderService service) {
        this.service = service;
    }

    /** e.g. /api/purchase-orders?storeId=S01&status=SUBMITTED&supplierId=SUP03&q=PO-01&page=0&size=20 */
    @GetMapping
    public Mono<Page<Summary>> search(@AuthenticationPrincipal UserAccount user,
                                      @RequestParam(required = false) String storeId,
                                      @RequestParam(required = false) String status,
                                      @RequestParam(required = false) String supplierId,
                                      @RequestParam(required = false) String q,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        return service.search(user, storeId, status, supplierId, q, page, size);
    }

    /** Low-stock items to reorder; managers get their own store, others pass storeId. */
    @GetMapping("/suggestions")
    public Flux<Suggestion> suggestions(@AuthenticationPrincipal UserAccount user, @RequestParam(required = false) String storeId) {
        return service.suggestions(user, storeId);
    }

    @GetMapping("/{id}")
    public Mono<Detail> get(@AuthenticationPrincipal UserAccount user, @PathVariable String id) {
        return service.get(user, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Detail> create(@AuthenticationPrincipal UserAccount user, @Validated @RequestBody CreateRequest request) {
        return service.create(user, request);
    }

    @PostMapping("/{id}/submit")
    public Mono<Detail> submit(@AuthenticationPrincipal UserAccount user, @PathVariable String id,
                               @Validated @RequestBody VersionRequest request) {
        return service.submit(user, id, request.version());
    }

    @PostMapping("/{id}/decision")
    public Mono<Detail> decide(@AuthenticationPrincipal UserAccount user, @PathVariable String id,
                               @Validated @RequestBody DecisionRequest request) {
        return service.decide(user, id, request);
    }

    @PostMapping("/{id}/receive")
    public Mono<Detail> receive(@AuthenticationPrincipal UserAccount user, @PathVariable String id,
                                @Validated @RequestBody ReceiveRequest request) {
        return service.receive(user, id, request);
    }

    @PostMapping("/{id}/close")
    public Mono<Detail> close(@AuthenticationPrincipal UserAccount user, @PathVariable String id,
                              @Validated @RequestBody CloseRequest request) {
        return service.close(user, id, request.reason(), request.version());
    }
}
