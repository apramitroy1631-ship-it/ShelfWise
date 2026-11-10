package com.shelfwise.core.purchasing;

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

/** Request and response bodies of the purchase-orders API. */
public final class PurchaseOrderDtos {

    private PurchaseOrderDtos() {
    }

    public record LineRequest(@NotBlank String sku, @NotNull @Min(value = 1, message = "quantity must be at least 1") Integer qty) {
    }

    /** A draft for one supplier (D24). Unit costs come from the catalogue, not from the request. */
    public record CreateRequest(@NotBlank String supplierId, @NotEmpty @Size(max = 200) List<@Valid LineRequest> lines) {
    }

    public record VersionRequest(@NotNull Long version) {
    }

    /** Finance's decision. A rejection needs a reason (design system §6: "Reject with reason"). */
    public record DecisionRequest(
            @NotBlank @Pattern(regexp = "APPROVED|REJECTED", message = "must be APPROVED or REJECTED") String decision,
            @Size(max = 500) String reason,
            @NotNull Long version) {
    }

    /** Goods arriving; any subset of lines and quantities (receiving in parts). */
    public record ReceiveRequest(@NotEmpty List<@Valid LineRequest> lines, @NotNull Long version) {
    }

    public record CloseRequest(@NotBlank @Size(max = 500) String reason, @NotNull Long version) {
    }

    public record Summary(
            String id,
            String storeId,
            String supplierId,
            String status,
            BigDecimal totalAmount,
            OffsetDateTime createdAt,
            int validApprovals,
            int approvalsNeeded,
            String complianceFlag,
            boolean requiresReapproval,
            boolean stuck,
            long version) {
    }

    public record LineView(String sku, String productName, int qtyOrdered, int qtyReceived, BigDecimal unitCost, BigDecimal lineTotal) {
    }

    public record ApprovalView(String approverId, String decision, OffsetDateTime decidedAt, boolean counts, Integer step) {
    }

    public record Detail(
            Summary summary,
            String createdBy,
            String closedReason,
            List<LineView> lines,
            List<ApprovalView> approvals) {
    }

    /** A low-stock item a manager may reorder (brief: "managers draft from low-stock items"). */
    public record Suggestion(String sku, String productName, String supplierId, int quantity, int reorderLevel,
                             int suggestedQty, BigDecimal unitCost) {
    }
}
