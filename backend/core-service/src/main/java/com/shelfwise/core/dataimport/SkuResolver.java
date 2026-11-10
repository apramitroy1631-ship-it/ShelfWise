package com.shelfwise.core.dataimport;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Decision D1. Two parts:
 * <p><b>Stored SKU.</b> A clean row keeps its UPPERCASE SKU. A variant row (trailing space or lowercase)
 * becomes its own product with a trimmed, lowercase SKU, e.g. {@code 'SW-SNK-00091 '} becomes {@code 'sw-snk-00091'}.
 * <p><b>Matching ladder</b> (how a row in another file finds its product). Compare SKUs trimmed and uppercased, then:
 * <ol>
 *   <li>no product: unknown (D12);</li>
 *   <li>exactly one product: use it, whatever the spelling;</li>
 *   <li>a clean/variant pair: an inventory or PO row uses its exact spelling; an order line with the clean spelling
 *       uses the clean product;</li>
 *   <li>a pair, order line with a messy spelling: the product whose price equals the sold price;</li>
 *   <li>still undecided: the clean product, flagged for the import report.</li>
 * </ol>
 */
final class SkuResolver {

    /** The outcome of matching one SKU. {@code storedSku} is empty when no product exists (step 1). */
    record Match(Optional<String> storedSku, int step, boolean flagged) {

        static Match unknown() {
            return new Match(Optional.empty(), 1, false);
        }

        static Match of(String sku, int step) {
            return new Match(Optional.of(sku), step, step == 5);
        }
    }

    private record Product(String rawSku, String storedSku, BigDecimal price) {
    }

    private final Map<String, List<Product>> byNormalised = new HashMap<>();

    /** @param rawSkuToPrice every product row's SKU exactly as written in products.csv, and its price (may be null) */
    SkuResolver(Map<String, BigDecimal> rawSkuToPrice) {
        rawSkuToPrice.forEach((raw, price) -> byNormalised
                .computeIfAbsent(normalise(raw), k -> new ArrayList<>())
                .add(new Product(raw, storedSku(raw), price)));
    }

    /** The SKU a products.csv row is stored under. */
    static String storedSku(String rawSku) {
        String trimmed = rawSku.strip();
        boolean clean = rawSku.equals(trimmed) && trimmed.equals(trimmed.toUpperCase(Locale.ROOT));
        return clean ? rawSku : trimmed.toLowerCase(Locale.ROOT);
    }

    static boolean isVariant(String rawSku) {
        return !storedSku(rawSku).equals(rawSku) || !rawSku.equals(rawSku.toUpperCase(Locale.ROOT));
    }

    /** For inventory and purchase-order rows: each variant has its own rows, so the exact spelling decides. */
    Match forStockOrPurchaseRow(String rawSku) {
        List<Product> candidates = byNormalised.get(normalise(rawSku));
        if (candidates == null) {
            return Match.unknown();
        }
        if (candidates.size() == 1) {
            return Match.of(candidates.get(0).storedSku(), 2);
        }
        return candidates.stream()
                .filter(p -> p.rawSku().equals(rawSku))
                .findFirst()
                .map(p -> Match.of(p.storedSku(), 3))
                .orElseGet(() -> Match.of(clean(candidates), 5));
    }

    /** For customer order lines: the price sold at breaks a tie between a clean product and its variant. */
    Match forOrderLine(String rawSku, BigDecimal soldPrice) {
        List<Product> candidates = byNormalised.get(normalise(rawSku));
        if (candidates == null) {
            return Match.unknown();
        }
        if (candidates.size() == 1) {
            return Match.of(candidates.get(0).storedSku(), 2);
        }
        if (rawSku.equals(normalise(rawSku))) {
            return Match.of(clean(candidates), 3);
        }
        if (soldPrice != null) {
            List<Product> samePrice = candidates.stream()
                    .filter(p -> p.price() != null && p.price().compareTo(soldPrice) == 0)
                    .toList();
            if (samePrice.size() == 1) {
                return Match.of(samePrice.get(0).storedSku(), 4);
            }
        }
        return Match.of(clean(candidates), 5);
    }

    private static String clean(List<Product> candidates) {
        return candidates.stream()
                .map(Product::storedSku)
                .filter(sku -> sku.equals(sku.toUpperCase(Locale.ROOT)))
                .findFirst()
                .orElse(candidates.get(0).storedSku());
    }

    private static String normalise(String sku) {
        return sku.strip().toUpperCase(Locale.ROOT);
    }
}
