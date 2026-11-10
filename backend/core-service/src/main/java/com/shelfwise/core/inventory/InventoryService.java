package com.shelfwise.core.inventory;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ResponseStatusException;

import com.shelfwise.core.auth.StoreAccess;
import com.shelfwise.core.auth.UserAccount;
import com.shelfwise.core.inventory.InventoryDtos.AdjustmentRequest;
import com.shelfwise.core.inventory.InventoryDtos.AdjustmentResult;
import com.shelfwise.core.inventory.InventoryDtos.Movement;
import com.shelfwise.core.inventory.InventoryDtos.StockItem;
import com.shelfwise.core.catalogue.CatalogueDtos.Page;

import reactor.core.publisher.Mono;

/** Stock per store, adjustments with a reason, and the history of every change (M10). */
@Service
public class InventoryService {

    static final int MAX_PAGE_SIZE = 100;

    private final InventoryRepository inventory;
    private final TransactionalOperator transaction;

    public InventoryService(InventoryRepository inventory, ReactiveTransactionManager transactionManager) {
        this.inventory = inventory;
        this.transaction = TransactionalOperator.create(transactionManager);
    }

    public Mono<Page<StockItem>> search(UserAccount user, String storeId, String text, boolean lowOnly, boolean recountOnly,
                                        String sort, String direction, int page, int size) {
        String store = StoreAccess.readableStore(user, storeId);
        String sortKey = sort == null ? "name" : sort;
        if (!InventoryRepository.SORT_COLUMNS.containsKey(sortKey)) {
            return Mono.error(badRequest("sort must be one of " + InventoryRepository.SORT_COLUMNS.keySet()));
        }
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            return Mono.error(badRequest("page must be 0 or more and size between 1 and " + MAX_PAGE_SIZE));
        }
        var search = new InventoryRepository.Search(store, text, lowOnly, recountOnly, sortKey, "desc".equalsIgnoreCase(direction), page, size);
        return inventory.search(search).collectList().zipWith(inventory.count(search))
                .map(t -> new Page<>(t.getT1(), page, size, t.getT2(), (t.getT2() + size - 1) / size));
    }

    public Mono<StockItem> get(UserAccount user, String storeId, String sku) {
        StoreAccess.readableStore(user, storeId);
        return inventory.find(storeId, sku).switchIfEmpty(Mono.error(notFound(storeId, sku)));
    }

    public Mono<List<Movement>> history(UserAccount user, String storeId, String sku, int page, int size) {
        StoreAccess.readableStore(user, storeId);
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            return Mono.error(badRequest("page must be 0 or more and size between 1 and " + MAX_PAGE_SIZE));
        }
        return inventory.find(storeId, sku).switchIfEmpty(Mono.error(notFound(storeId, sku)))
                .then(inventory.history(storeId, sku, page, size).collectList());
    }

    /**
     * Applies an adjustment and records it in the history, in one transaction.
     * Allowed on archived products too, so leftover stock can be written off (D3).
     */
    public Mono<AdjustmentResult> adjust(UserAccount user, String storeId, String sku, AdjustmentRequest r) {
        StoreAccess.checkCanChangeStock(user, storeId);
        String note = r.note() == null || r.note().isBlank() ? null : r.note().strip();
        Mono<Movement> change = switch (r.type()) {
            case "DAMAGED" -> r.quantity() <= 0
                    ? Mono.error(badRequest("damaged quantity must be at least 1"))
                    : take(user, storeId, sku, -r.quantity(), "ADJUSTMENT_DAMAGED", note);
            case "OTHER" -> r.quantity() == 0 || note == null
                    ? Mono.error(badRequest("an OTHER adjustment needs a non-zero quantity and a note explaining it"))
                    : take(user, storeId, sku, r.quantity(), "ADJUSTMENT_OTHER", note);
            case "RECOUNT" -> r.quantity() < 0
                    ? Mono.error(badRequest("a counted quantity can't be negative"))
                    : recount(user, storeId, sku, r.quantity(), note);
            default -> Mono.error(badRequest("unknown adjustment type"));
        };
        return change
                .flatMap(movement -> inventory.find(storeId, sku).map(item -> new AdjustmentResult(item, movement)))
                .as(transaction::transactional);
    }

    private Mono<Movement> take(UserAccount user, String storeId, String sku, int delta, String reason, String note) {
        return inventory.addAtomically(storeId, sku, delta)
                .switchIfEmpty(Mono.defer(() -> inventory.find(storeId, sku)
                        .switchIfEmpty(Mono.error(notFound(storeId, sku)))
                        .flatMap(item -> Mono.error(new ResponseStatusException(HttpStatus.CONFLICT,
                                "Only " + item.quantity() + " in stock; this change would take it below 0")))))
                .flatMap(after -> inventory.recordMovement(storeId, sku, delta, after, reason, note, null, null, user.id()));
    }

    private Mono<Movement> recount(UserAccount user, String storeId, String sku, int counted, String note) {
        return inventory.setCounted(storeId, sku, counted)
                .switchIfEmpty(Mono.error(notFound(storeId, sku)))
                .flatMap(change -> inventory.recordMovement(storeId, sku, change[1] - change[0], change[1],
                        "ADJUSTMENT_RECOUNT", note == null ? "Counted " + counted + " on the shelf" : note, null, null, user.id()));
    }

    private static ResponseStatusException notFound(String storeId, String sku) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "No stock record for '" + sku + "' in store " + storeId);
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
