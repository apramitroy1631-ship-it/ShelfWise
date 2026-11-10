package com.shelfwise.core.inventory;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.shelfwise.core.auth.UserAccount;
import com.shelfwise.core.catalogue.CatalogueDtos.Page;
import com.shelfwise.core.inventory.InventoryDtos.AdjustmentRequest;
import com.shelfwise.core.inventory.InventoryDtos.AdjustmentResult;
import com.shelfwise.core.inventory.InventoryDtos.Movement;
import com.shelfwise.core.inventory.InventoryDtos.StockItem;

import reactor.core.publisher.Mono;

/** Inventory API. Store scope is checked on the server for every call (see StoreAccess). */
@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private final InventoryService inventory;

    public InventoryController(InventoryService inventory) {
        this.inventory = inventory;
    }

    /** e.g. /api/inventory?storeId=S01&q=chips&lowOnly=true&sort=quantity&page=0&size=20 */
    @GetMapping
    public Mono<Page<StockItem>> search(@AuthenticationPrincipal UserAccount user,
                                        @RequestParam(required = false) String storeId,
                                        @RequestParam(required = false) String q,
                                        @RequestParam(defaultValue = "false") boolean lowOnly,
                                        @RequestParam(defaultValue = "false") boolean recountOnly,
                                        @RequestParam(required = false) String sort,
                                        @RequestParam(required = false) String direction,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        return inventory.search(user, storeId, q, lowOnly, recountOnly, sort, direction, page, size);
    }

    @GetMapping("/{storeId}/{sku}")
    public Mono<StockItem> get(@AuthenticationPrincipal UserAccount user, @PathVariable String storeId, @PathVariable String sku) {
        return inventory.get(user, storeId, sku);
    }

    @GetMapping("/{storeId}/{sku}/history")
    public Mono<List<Movement>> history(@AuthenticationPrincipal UserAccount user, @PathVariable String storeId,
                                        @PathVariable String sku,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "50") int size) {
        return inventory.history(user, storeId, sku, page, size);
    }

    @PostMapping("/{storeId}/{sku}/adjustments")
    public Mono<AdjustmentResult> adjust(@AuthenticationPrincipal UserAccount user, @PathVariable String storeId,
                                         @PathVariable String sku, @Validated @RequestBody AdjustmentRequest request) {
        return inventory.adjust(user, storeId, sku, request);
    }
}
