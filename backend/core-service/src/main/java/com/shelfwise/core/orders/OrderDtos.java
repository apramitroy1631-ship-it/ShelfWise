package com.shelfwise.core.orders;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request and response bodies of the orders API. */
public final class OrderDtos {

    private OrderDtos() {
    }

    /** The finished multi-step form: customer details and the items. The store is the manager's own store. */
    public record CreateOrderRequest(
            @NotBlank @Size(max = 120) String customerName,
            @NotBlank @Pattern(regexp = "\\+91 [6-9]\\d{9}", message = "must be an Indian mobile number like +91 9876543210")
            String customerPhone,
            @NotEmpty @Size(max = 100) List<@Valid LineRequest> lines) {
    }

    public record LineRequest(@NotBlank String sku, @NotNull @Min(value = 1, message = "quantity must be at least 1") Integer qty) {
    }

    public record StatusChangeRequest(
            @NotBlank @Pattern(regexp = "PACKED|SHIPPED|DELIVERED|CANCELLED", message = "must be PACKED, SHIPPED, DELIVERED or CANCELLED")
            String to,
            @NotNull Long version) {
    }

    /** List row. The phone is always masked in lists (D16). */
    public record OrderSummary(
            String id,
            String storeId,
            String status,
            String customerName,
            String customerPhone,
            BigDecimal totalAmount,
            OffsetDateTime createdAt,
            String reviewStatus,
            boolean stuck,
            long version) {
    }

    public record LineView(String sku, String productSku, String productName, int qty, BigDecimal unitPrice, BigDecimal lineTotal) {
    }

    /** One order. The phone is shown in full only to managers of that store (D16). */
    public record OrderDetail(
            String id,
            String storeId,
            String placedBy,
            String status,
            String customerName,
            String customerPhone,
            BigDecimal totalAmount,
            OffsetDateTime createdAt,
            String reviewStatus,
            String source,
            boolean stuck,
            long version,
            List<LineView> lines) {
    }
}
