package com.shelfwise.core.inventory;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request and response bodies of the inventory API. */
public final class InventoryDtos {

    private InventoryDtos() {
    }

    /** One product's stock in one store. {@code low} = at or below its reorder level (D7). */
    public record StockItem(
            String storeId,
            String sku,
            String productName,
            String category,
            String productStatus,
            BigDecimal priceInr,
            int quantity,
            int reorderLevel,
            boolean low,
            boolean recountNeeded,
            OffsetDateTime lastCountedAt,
            long version) {
    }

    /** One change to stock, with its reason (brief: "adjustments with a reason, and a history of changes"). */
    public record Movement(
            long id,
            int delta,
            int quantityAfter,
            String reason,
            String note,
            String referenceType,
            String referenceId,
            String actorUserId,
            String actorName,         // for display (N6); null for system changes (import, jobs)
            OffsetDateTime at) {
    }

    /**
     * An adjustment by a store manager or admin.
     * <ul>
     *   <li>{@code DAMAGED}: {@code quantity} units removed (a positive number).</li>
     *   <li>{@code RECOUNT}: {@code quantity} is the counted number on the shelf; clears "recount needed" (D5).</li>
     *   <li>{@code OTHER}: {@code quantity} is a signed change (e.g. -2 or +3); a note is required.</li>
     * </ul>
     */
    public record AdjustmentRequest(
            @NotBlank @Pattern(regexp = "DAMAGED|RECOUNT|OTHER", message = "must be DAMAGED, RECOUNT or OTHER") String type,
            @NotNull Integer quantity,
            @Size(max = 500) String note) {
    }

    public record AdjustmentResult(StockItem item, Movement movement) {
    }
}
