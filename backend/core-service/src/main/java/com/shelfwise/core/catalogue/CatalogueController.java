package com.shelfwise.core.catalogue;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.shelfwise.core.auth.UserAccount;
import com.shelfwise.core.catalogue.CatalogueDtos.CreateProductRequest;
import com.shelfwise.core.catalogue.CatalogueDtos.Page;
import com.shelfwise.core.catalogue.CatalogueDtos.ProductView;
import com.shelfwise.core.catalogue.CatalogueDtos.StatusChangeRequest;
import com.shelfwise.core.catalogue.CatalogueDtos.UpdateProductRequest;

import reactor.core.publisher.Mono;

/**
 * Catalogue API. Anyone signed in can browse; only admins can change products
 * (enforced in SecurityConfig for POST and PUT on /api/products/**).
 */
@RestController
@RequestMapping("/api/products")
public class CatalogueController {

    private final CatalogueService catalogue;

    public CatalogueController(CatalogueService catalogue) {
        this.catalogue = catalogue;
    }

    /** e.g. /api/products?q=chips&category=Snacks&status=ACTIVE&sort=price&direction=desc&page=0&size=20 */
    @GetMapping
    public Mono<Page<ProductView>> search(@RequestParam(required = false) String q,
                                          @RequestParam(required = false) String category,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) String sort,
                                          @RequestParam(required = false) String direction,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        return catalogue.search(q, category, status, sort, direction, page, size);
    }

    @GetMapping("/categories")
    public Mono<List<String>> categories() {
        return catalogue.categories();
    }

    /** SKUs are case-sensitive: 'SW-HOM-00033' and 'sw-hom-00033' are different products (D1). */
    @GetMapping("/{sku}")
    public Mono<ProductView> get(@PathVariable String sku) {
        return catalogue.get(sku);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ProductView> create(@Validated @RequestBody CreateProductRequest request,
                                    @AuthenticationPrincipal UserAccount admin) {
        return catalogue.create(request, admin.id());
    }

    @PutMapping("/{sku}")
    public Mono<ProductView> update(@PathVariable String sku, @Validated @RequestBody UpdateProductRequest request,
                                    @AuthenticationPrincipal UserAccount admin) {
        return catalogue.update(sku, request, admin.id());
    }

    @PostMapping("/{sku}/archive")
    public Mono<ProductView> archive(@PathVariable String sku, @Validated @RequestBody StatusChangeRequest request,
                                     @AuthenticationPrincipal UserAccount admin) {
        return catalogue.archive(sku, request.version(), request.reason(), admin.id());
    }

    @PostMapping("/{sku}/unarchive")
    public Mono<ProductView> unarchive(@PathVariable String sku, @Validated @RequestBody StatusChangeRequest request,
                                       @AuthenticationPrincipal UserAccount admin) {
        return catalogue.unarchive(sku, request.version(), request.reason(), admin.id());
    }
}
