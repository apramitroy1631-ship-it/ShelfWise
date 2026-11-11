package com.shelfwise.core.dashboard;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Response of the dashboard API (M22). Money in rupees; dates are IST days (D9). */
public final class DashboardDtos {

    private DashboardDtos() {
    }

    /**
     * @param revenue          DELIVERED orders only (user decision 2026-11-11)
     * @param orderCount       every order placed in the range, whatever its status
     * @param ordersByStatus   the same orders split by status, so cancellations stay visible
     * @param lowStockCount    items at or below their reorder level right now (archived left out, D3/D7); not date-ranged
     */
    public record Summary(
            String storeId,
            LocalDate from,
            LocalDate to,
            BigDecimal revenue,
            long deliveredOrders,
            long orderCount,
            Map<String, Long> ordersByStatus,
            long lowStockCount,
            long outOfStockCount,
            List<TopProduct> topProducts,
            UnknownProducts unknownProducts,
            String trendBy,
            List<TrendPoint> trend) {
    }

    /** Ranked by revenue from delivered orders; units shown too. */
    public record TopProduct(String sku, String name, long units, BigDecimal revenue) {
    }

    /** D12: lines whose product code isn't in the catalogue count in revenue under their own heading. */
    public record UnknownProducts(long units, BigDecimal revenue) {
    }

    /** One store on one day (or month): delivered revenue and orders placed. */
    public record TrendPoint(LocalDate period, String storeId, BigDecimal revenue, long orders) {
    }
}
