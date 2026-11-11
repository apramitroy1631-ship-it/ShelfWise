package com.shelfwise.core.orders;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.shelfwise.core.auth.StoreAccess;
import com.shelfwise.core.auth.UserAccount;
import com.shelfwise.core.orders.OrderRepository.ExportRow;

import reactor.core.publisher.Flux;

/**
 * CSV export of the filtered order list (M22). Same filters and store scope as the list. Rows are streamed as
 * they are read, so a large export never sits in memory. Phones are always masked (D16); orders waiting for
 * review are left out (D10). Times are IST (D9).
 */
@Component
public class OrderExport {

    static final String HEADER = "Order ID,Store,Status,Placed (IST),Customer,Customer phone,Units,Total (INR)\r\n";
    private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final OrderRepository orders;

    public OrderExport(OrderRepository orders) {
        this.orders = orders;
    }

    public Flux<String> csv(UserAccount user, String storeId, String status, String text, LocalDate from, LocalDate to) {
        String store = StoreAccess.readableStore(user, storeId);
        if (from != null && to != null && to.isBefore(from)) {
            return Flux.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "'to' is before 'from'"));
        }
        var search = new OrderRepository.Search(store, status, text,
                from == null ? null : from.atStartOfDay().atOffset(IST),
                to == null ? null : to.plusDays(1).atStartOfDay().atOffset(IST), 0, 0);
        return Flux.just(HEADER).concatWith(orders.exportRows(search).map(OrderExport::line));
    }

    /** e.g. "orders-S01-2026-10-01-to-2026-10-31.csv" */
    public static String fileName(UserAccount user, String storeId, LocalDate from, LocalDate to) {
        String store = StoreAccess.readableStore(user, storeId);
        return "orders-" + (store == null ? "all-stores" : store)
                + (from == null ? "" : "-from-" + from) + (to == null ? "" : "-to-" + to) + ".csv";
    }

    private static String line(ExportRow r) {
        OrderRepository.OrderRow o = r.order();
        return String.join(",",
                cell(o.id()), cell(o.storeId()), cell(o.status()),
                cell(o.createdAt().withOffsetSameInstant(IST).format(WHEN)),
                cell(safeText(o.customerName())),
                cell(OrderService.maskPhone(o.customerPhone())),
                Long.toString(r.units()),
                o.totalAmount().toPlainString()) + "\r\n";
    }

    /** Quotes a value when it holds a comma, quote or line break (RFC 4180). */
    static String cell(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    /**
     * A customer name typed as "=HYPERLINK(...)" would run as a formula when the file is opened in Excel
     * (CSV injection). Such text is prefixed with an apostrophe so it shows as plain text.
     */
    static String safeText(String value) {
        if (value != null && !value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0) {
            return "'" + value;
        }
        return value;
    }
}
