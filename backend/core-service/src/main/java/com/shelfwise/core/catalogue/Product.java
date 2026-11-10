package com.shelfwise.core.catalogue;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A product as stored. {@code priceInr} may be null only for imported products (D2):
 * such a product can't be sold until an admin sets a price.
 */
public record Product(
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
        long version) {

    public boolean archived() {
        return "ARCHIVED".equals(status);
    }

    /** Can go on a new customer order: active and priced (D2, D3; part of the D30 "can this be sold?" rule). */
    public boolean sellable() {
        return !archived() && priceInr != null;
    }
}
