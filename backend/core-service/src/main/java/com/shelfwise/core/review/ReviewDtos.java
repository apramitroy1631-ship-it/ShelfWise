package com.shelfwise.core.review;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request and response bodies of the admin review queue (D10, and D11's last step). */
public final class ReviewDtos {

    private ReviewDtos() {
    }

    /** What is wrong with a line. Only flagged lines can be changed by a correction. */
    public enum Problem {
        /** D10: a quantity of 0 or below in the imported file. The admin enters the right quantity. */
        NEGATIVE_QTY,
        /** D11: a missing price no evidence could prove. The admin sets the price. */
        NO_PRICE
    }

    /**
     * One order line. {@code originalQty} / {@code originalUnitPrice} are filled once an order has been corrected,
     * so the before-and-after is always visible; while it waits, the current values ARE the original ones.
     */
    public record ReviewLine(String sku, String productSku, String productName, int qty, BigDecimal unitPrice,
                             BigDecimal lineTotal, Problem problem, Integer originalQty, BigDecimal originalUnitPrice) {
    }

    /** One flagged order: what it is, what is wrong, and (once decided) who decided what and why. */
    public record ReviewItem(String orderId, String storeId, String status, OffsetDateTime createdAt, String customerName,
                             BigDecimal totalAmount, BigDecimal originalTotal, String reviewStatus, String reviewNote,
                             String reviewedBy, String reviewedByName, OffsetDateTime reviewedAt, long version,
                             List<String> problems, List<ReviewLine> lines) {
    }

    /** {@code waiting} is always the number still waiting, whichever list was asked for (it drives the menu badge). */
    public record ReviewList(long waiting, List<ReviewItem> items) {
    }

    /** The fix for one flagged line: a quantity for NEGATIVE_QTY, a price for NO_PRICE. */
    public record LineFix(@NotBlank String sku,
                          @Min(value = 1, message = "quantity must be at least 1") Integer qty,
                          @DecimalMin(value = "0.01", message = "price must be above 0") BigDecimal unitPrice) {
    }

    /** Correct: every flagged line gets its fix, plus a reason. The order then counts in revenue. */
    public record CorrectRequest(@NotNull Long version,
                                 @NotEmpty @Size(max = 100) List<@Valid LineFix> lines,
                                 @NotBlank @Size(max = 500) String reason) {
    }

    /** Reject: the order stays out of revenue for good, with a reason. */
    public record RejectRequest(@NotNull Long version, @NotBlank @Size(max = 500) String reason) {
    }
}
