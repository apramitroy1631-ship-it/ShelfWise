package com.shelfwise.core.dataimport;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Decision D1, using real pairs from products.csv. */
class SkuResolverTest {

    private final SkuResolver resolver = new SkuResolver(products());

    private static Map<String, BigDecimal> products() {
        Map<String, BigDecimal> rows = new LinkedHashMap<>();
        rows.put("SW-SNK-00091", new BigDecimal("25"));    // clean
        rows.put("SW-SNK-00091 ", new BigDecimal("28"));   // variant: trailing space
        rows.put("SW-HOM-00033", new BigDecimal("96"));    // clean
        rows.put("sw-hom-00033", new BigDecimal("99"));    // variant: lowercase
        rows.put("SW-ELE-00079", new BigDecimal("296"));   // clean
        rows.put("SW-ELE-00079 ", new BigDecimal("296"));  // variant with the SAME price
        rows.put("SW-SNK-00078", new BigDecimal("61"));    // only one product
        return rows;
    }

    @Test
    void cleanRowsKeepTheirSkuAndVariantsBecomeLowercase() {
        assertThat(SkuResolver.storedSku("SW-SNK-00091")).isEqualTo("SW-SNK-00091");
        assertThat(SkuResolver.storedSku("SW-SNK-00091 ")).isEqualTo("sw-snk-00091");
        assertThat(SkuResolver.storedSku("sw-hom-00033")).isEqualTo("sw-hom-00033");
    }

    @Test
    void stockAndPurchaseRowsUseTheirExactSpelling() {
        assertThat(resolver.forStockOrPurchaseRow("SW-SNK-00091 ")).isEqualTo(SkuResolver.Match.of("sw-snk-00091", 3));
        assertThat(resolver.forStockOrPurchaseRow("SW-SNK-00091")).isEqualTo(SkuResolver.Match.of("SW-SNK-00091", 3));
    }

    @Test
    void messySpellingOfAProductWithNoVariantFindsIt_step2() {
        // ORD-010060 used 'sw-snk-00078'
        assertThat(resolver.forOrderLine("sw-snk-00078", new BigDecimal("61"))).isEqualTo(SkuResolver.Match.of("SW-SNK-00078", 2));
    }

    @Test
    void orderLineWithTheCleanSpellingUsesTheCleanProduct_step3() {
        assertThat(resolver.forOrderLine("SW-HOM-00033", new BigDecimal("96"))).isEqualTo(SkuResolver.Match.of("SW-HOM-00033", 3));
    }

    @Test
    void messySpellingOfAPairIsDecidedByTheSoldPrice_step4() {
        // ORD-007387: 'sw-hom-00033' sold at 96 = the clean product's price, not the variant's 99
        assertThat(resolver.forOrderLine("sw-hom-00033", new BigDecimal("96"))).isEqualTo(SkuResolver.Match.of("SW-HOM-00033", 4));
    }

    @Test
    void undecidedFallsBackToTheCleanProductAndIsFlagged_step5() {
        SkuResolver.Match samePrice = resolver.forOrderLine("sw-ele-00079", new BigDecimal("296"));
        SkuResolver.Match noPrice = resolver.forOrderLine("sw-hom-00033", null);

        assertThat(samePrice.storedSku()).contains("SW-ELE-00079");
        assertThat(samePrice.flagged()).isTrue();
        assertThat(noPrice.storedSku()).contains("SW-HOM-00033");
        assertThat(noPrice.flagged()).isTrue();
    }

    @Test
    void unknownProductIsReportedNotGuessed_step1() {
        assertThat(resolver.forOrderLine("SW-ZZZ-92676", new BigDecimal("195"))).isEqualTo(SkuResolver.Match.unknown());
        assertThat(resolver.forStockOrPurchaseRow("SW-ZZZ-92676")).isEqualTo(SkuResolver.Match.unknown());
    }
}
