package com.shelfwise.core.dashboard;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.shelfwise.core.auth.StoreAccess;
import com.shelfwise.core.auth.UserAccount;
import com.shelfwise.core.dashboard.DashboardDtos.Summary;

import reactor.core.publisher.Mono;

/**
 * Dashboard (M22): revenue, order count, top products, low-stock count and the trend by store and date.
 * Store managers see their own store; admin and finance see all stores or one. Dates are IST days (D9);
 * with no dates, everything is included.
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
    private static final int TOP = 10;

    private final DashboardRepository dashboard;

    public DashboardController(DashboardRepository dashboard) {
        this.dashboard = dashboard;
    }

    /** e.g. /api/dashboard?storeId=S01&from=2026-10-01&to=2026-10-31&trendBy=day */
    @GetMapping
    public Mono<Summary> summary(@AuthenticationPrincipal UserAccount user,
                                 @RequestParam(required = false) String storeId,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                 @RequestParam(required = false) String trendBy) {
        String store = StoreAccess.readableStore(user, storeId);
        if (from != null && to != null && to.isBefore(from)) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "'to' is before 'from'"));
        }
        if (trendBy != null && !trendBy.equals("day") && !trendBy.equals("month")) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "trendBy must be day or month"));
        }
        // a daily chart over more than about three months is unreadable, so longer ranges default to months
        String unit = trendBy != null ? trendBy
                : (from != null && to != null && ChronoUnit.DAYS.between(from, to) <= 92) ? "day" : "month";
        var range = new DashboardRepository.Range(store,
                from == null ? null : from.atStartOfDay().atOffset(IST),
                to == null ? null : to.plusDays(1).atStartOfDay().atOffset(IST));

        return Mono.zip(dashboard.totals(range), dashboard.lowStock(store), dashboard.topProducts(range, TOP).collectList(),
                        dashboard.unknownProducts(range), dashboard.trend(range, unit).collectList())
                .map(t -> {
                    Map<String, Object> totals = t.getT1();
                    @SuppressWarnings("unchecked")
                    Map<String, Long> byStatus = (Map<String, Long>) totals.get("byStatus");
                    return new Summary(store, from, to, (java.math.BigDecimal) totals.get("revenue"), (Long) totals.get("delivered"),
                            (Long) totals.get("orders"), byStatus, t.getT2()[0], t.getT2()[1], t.getT3(), t.getT4(), unit, t.getT5());
                });
    }
}
