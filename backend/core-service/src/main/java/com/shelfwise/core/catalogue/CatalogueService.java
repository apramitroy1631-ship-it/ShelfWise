package com.shelfwise.core.catalogue;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ResponseStatusException;

import com.shelfwise.core.audit.AuditLog;
import com.shelfwise.core.catalogue.CatalogueDtos.CreateProductRequest;
import com.shelfwise.core.catalogue.CatalogueDtos.Page;
import com.shelfwise.core.catalogue.CatalogueDtos.ProductView;
import com.shelfwise.core.catalogue.CatalogueDtos.UpdateProductRequest;

import reactor.core.publisher.Mono;

/** Catalogue rules. Every change is saved together with its audit entry, in one transaction. */
@Service
public class CatalogueService {

    static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> STATUSES = Set.of("ACTIVE", "ARCHIVED", "ALL");
    private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);

    private final ProductRepository products;
    private final AuditLog audit;
    private final TransactionalOperator transaction;

    public CatalogueService(ProductRepository products, AuditLog audit, ReactiveTransactionManager transactionManager) {
        this.products = products;
        this.audit = audit;
        this.transaction = TransactionalOperator.create(transactionManager);
    }

    /** Browse: active products by default (archived ones stay out of the way, D3), newest rules applied by the database. */
    public Mono<Page<ProductView>> search(String text, String category, String status, String sort, String direction,
                                          int page, int size) {
        String statusFilter = status == null ? "ACTIVE" : status.toUpperCase();
        if (!STATUSES.contains(statusFilter)) {
            return Mono.error(badRequest("status must be ACTIVE, ARCHIVED or ALL"));
        }
        String sortKey = sort == null ? "name" : sort;
        if (!ProductRepository.SORT_COLUMNS.containsKey(sortKey)) {
            return Mono.error(badRequest("sort must be one of " + ProductRepository.SORT_COLUMNS.keySet()));
        }
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            return Mono.error(badRequest("page must be 0 or more and size between 1 and " + MAX_PAGE_SIZE));
        }
        var search = new ProductRepository.Search(text, category, statusFilter, sortKey, "desc".equalsIgnoreCase(direction), page, size);
        return products.search(search).map(ProductView::from).collectList()
                .zipWith(products.count(search))
                .map(t -> Page.of(t.getT1(), page, size, t.getT2()));
    }

    public Mono<ProductView> get(String sku) {
        return products.findBySku(sku).map(ProductView::from).switchIfEmpty(Mono.error(notFound(sku)));
    }

    public Mono<ProductView> create(CreateProductRequest r, String adminId) {
        Product product = new Product(r.sku(), r.name().strip(), r.category().strip(), r.priceInr(), r.costPriceInr(),
                r.gstRate(), r.supplierId(), "ACTIVE", r.reorderLevel(), LocalDate.now(IST), 0);
        return products.existsIgnoringCase(r.sku())
                .flatMap(exists -> exists
                        ? Mono.error(new ResponseStatusException(HttpStatus.CONFLICT,
                                "A product with SKU '" + r.sku() + "' already exists (SKUs that differ only by letter case are not allowed)"))
                        : products.supplierExists(r.supplierId()))
                .flatMap(supplierOk -> supplierOk ? products.insert(product) : Mono.error(badRequest("unknown or inactive supplier '" + r.supplierId() + "'")))
                .then(audit.record(adminId, "PRODUCT_CREATED", "PRODUCT", r.sku(), null, ProductView.from(product), null))
                .then(products.findBySku(r.sku()))
                .map(ProductView::from)
                .as(transaction::transactional);
    }

    /**
     * Edits a product. Refused with 409 if someone saved it since the admin loaded it.
     * A price can be set on an imported unpriced product (D2) but never removed.
     */
    public Mono<ProductView> update(String sku, UpdateProductRequest r, String adminId) {
        return products.findBySku(sku)
                .switchIfEmpty(Mono.error(notFound(sku)))
                .flatMap(before -> {
                    if (r.priceInr() == null && before.priceInr() != null) {
                        return Mono.error(badRequest("price can be changed but not removed"));
                    }
                    Product after = new Product(sku, r.name().strip(), r.category().strip(), r.priceInr(), r.costPriceInr(),
                            r.gstRate(), r.supplierId(), before.status(), r.reorderLevel(), before.cataloguedOn(), r.version() + 1);
                    return products.supplierExists(r.supplierId())
                            .flatMap(ok -> ok ? products.update(after, r.version())
                                    : Mono.error(badRequest("unknown or inactive supplier '" + r.supplierId() + "'")))
                            .flatMap(saved -> saved
                                    ? audit.record(adminId, priceSetFirstTime(before, after) ? "PRODUCT_PRICE_SET" : "PRODUCT_UPDATED",
                                            "PRODUCT", sku, ProductView.from(before), ProductView.from(after), null)
                                    : Mono.error(changedMeanwhile(sku)));
                })
                .then(products.findBySku(sku))
                .map(ProductView::from)
                .as(transaction::transactional);
    }

    /** D3: archiving stops new business (no new orders or POs) but keeps stock and history. */
    public Mono<ProductView> archive(String sku, long version, String reason, String adminId) {
        return changeStatus(sku, "ACTIVE", "ARCHIVED", "PRODUCT_ARCHIVED", version, reason, adminId);
    }

    /** D3: unarchiving restores new business without touching stock or history. The response carries the price to confirm. */
    public Mono<ProductView> unarchive(String sku, long version, String reason, String adminId) {
        return changeStatus(sku, "ARCHIVED", "ACTIVE", "PRODUCT_UNARCHIVED", version, reason, adminId);
    }

    public Mono<java.util.List<String>> categories() {
        return products.categories().collectList();
    }

    private Mono<ProductView> changeStatus(String sku, String from, String to, String action, long version,
                                           String reason, String adminId) {
        return products.findBySku(sku)
                .switchIfEmpty(Mono.error(notFound(sku)))
                .flatMap(before -> {
                    if (!before.status().equals(from)) {
                        return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT,
                                "Product '" + sku + "' is already " + before.status().toLowerCase()));
                    }
                    return products.changeStatus(sku, from, to, version)
                            .flatMap(saved -> saved
                                    ? audit.record(adminId, action, "PRODUCT", sku, Map.of("status", from), Map.of("status", to), reason)
                                    : Mono.error(changedMeanwhile(sku)));
                })
                .then(products.findBySku(sku))
                .map(ProductView::from)
                .as(transaction::transactional);
    }

    private static boolean priceSetFirstTime(Product before, Product after) {
        return before.priceInr() == null && after.priceInr() != null;
    }

    private static ResponseStatusException notFound(String sku) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "No product with SKU '" + sku + "'");
    }

    private static ResponseStatusException changedMeanwhile(String sku) {
        return new ResponseStatusException(HttpStatus.CONFLICT,
                "Product '" + sku + "' was changed by someone else since you loaded it; reload and try again");
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
