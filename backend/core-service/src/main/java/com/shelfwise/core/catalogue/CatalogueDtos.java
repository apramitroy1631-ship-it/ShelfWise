package com.shelfwise.core.catalogue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request and response bodies of the catalogue API. */
public final class CatalogueDtos {

    private CatalogueDtos() {
    }

    /** One page of results, with what's needed to show "page 3 of 40". */
    public record Page<T>(List<T> items, int page, int size, long totalItems, long totalPages) {
        static <T> Page<T> of(List<T> items, int page, int size, long total) {
            return new Page<>(items, page, size, total, (total + size - 1) / size);
        }
    }

    public record ProductView(
            String sku,
            String name,
            String category,
            BigDecimal priceInr,
            BigDecimal costPriceInr,
            BigDecimal gstRate,
            String supplierId,
            String status,
            int reorderLevel,
            LocalDate cataloguedOn,
            boolean sellable,
            long version) {

        static ProductView from(Product p) {
            return new ProductView(p.sku(), p.name(), p.category(), p.priceInr(), p.costPriceInr(), p.gstRate(),
                    p.supplierId(), p.status(), p.reorderLevel(), p.cataloguedOn(), p.sellable(), p.version());
        }
    }

    /** New products use the clean SKU format, so no new case-or-space look-alikes appear (D1). */
    public record CreateProductRequest(
            @NotBlank @Pattern(regexp = "SW-[A-Z]{3}-\\d{5}", message = "must look like SW-ABC-12345") String sku,
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Size(max = 100) String category,
            @NotNull @DecimalMin(value = "0.01", message = "a new product needs a price above 0 (D2)") BigDecimal priceInr,
            @NotNull @DecimalMin("0") BigDecimal costPriceInr,
            @NotNull @DecimalMin("0") BigDecimal gstRate,
            @NotBlank String supplierId,
            @NotNull @Min(0) Integer reorderLevel) {
    }

    /** {@code version} is the version the admin last saw: if someone saved since, the edit is refused (409). */
    public record UpdateProductRequest(
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Size(max = 100) String category,
            @DecimalMin(value = "0.01", message = "price must be above 0") BigDecimal priceInr,
            @NotNull @DecimalMin("0") BigDecimal costPriceInr,
            @NotNull @DecimalMin("0") BigDecimal gstRate,
            @NotBlank String supplierId,
            @NotNull @Min(0) Integer reorderLevel,
            @NotNull Long version) {
    }

    /** Archive and unarchive take the version too, and an optional reason for the audit log. */
    public record StatusChangeRequest(@NotNull Long version, @Size(max = 500) String reason) {
    }
}
