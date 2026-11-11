package com.shelfwise.core.orders;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ResponseStatusException;

import com.shelfwise.core.auth.StoreAccess;
import com.shelfwise.core.auth.UserAccount;
import com.shelfwise.core.catalogue.CatalogueDtos.Page;
import com.shelfwise.core.catalogue.Product;
import com.shelfwise.core.catalogue.ProductRepository;
import com.shelfwise.core.config.AppSettings;
import com.shelfwise.core.inventory.InventoryRepository;
import com.shelfwise.core.orders.OrderDtos.CreateOrderRequest;
import com.shelfwise.core.orders.OrderDtos.LineRequest;
import com.shelfwise.core.orders.OrderDtos.OrderDetail;
import com.shelfwise.core.orders.OrderDtos.OrderSummary;
import com.shelfwise.core.orders.OrderRepository.OrderRow;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Customer orders (M11). Stock follows the order (D29): it is taken when the order is created and
 * given back only if the order is cancelled (allowed from Pending or Packed). Each stock change is the
 * same atomic "only if enough is left" update as inventory adjustments, so overlapping orders can't oversell.
 */
@Service
public class OrderService {

    static final int MAX_PAGE_SIZE = 100;
    private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
    private static final Set<String> OPEN = Set.of("PENDING", "PACKED", "SHIPPED");

    /** The order lifecycle from the brief; anything not listed is refused. */
    private static final Map<String, Set<String>> NEXT = Map.of(
            "PENDING", Set.of("PACKED", "CANCELLED"),
            "PACKED", Set.of("SHIPPED", "CANCELLED"),
            "SHIPPED", Set.of("DELIVERED"));

    private final OrderRepository orders;
    private final ProductRepository products;
    private final InventoryRepository inventory;
    private final AppSettings settings;
    private final TransactionalOperator transaction;

    public OrderService(OrderRepository orders, ProductRepository products, InventoryRepository inventory,
                        AppSettings settings, ReactiveTransactionManager transactionManager) {
        this.orders = orders;
        this.products = products;
        this.inventory = inventory;
        this.settings = settings;
        this.transaction = TransactionalOperator.create(transactionManager);
    }

    // --- reading -----------------------------------------------------------

    public Mono<Page<OrderSummary>> search(UserAccount user, String storeId, String status, String text,
                                           LocalDate from, LocalDate to, int page, int size) {
        String store = StoreAccess.readableStore(user, storeId);
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            return Mono.error(badRequest("page must be 0 or more and size between 1 and " + MAX_PAGE_SIZE));
        }
        var search = new OrderRepository.Search(store, status, text,
                from == null ? null : from.atStartOfDay().atOffset(IST),           // dates are IST days (D9)
                to == null ? null : to.plusDays(1).atStartOfDay().atOffset(IST), page, size);
        return stuckAfter().flatMap(stuckDays -> orders.search(search)
                .map(o -> new OrderSummary(o.id(), o.storeId(), o.status(), o.customerName(), maskPhone(o.customerPhone()),
                        o.totalAmount(), o.createdAt(), o.reviewStatus(), isStuck(o, stuckDays), o.version()))
                .collectList()
                .zipWith(orders.count(search))
                .map(t -> new Page<>(t.getT1(), page, size, t.getT2(), (t.getT2() + size - 1) / size)));
    }

    public Mono<OrderDetail> get(UserAccount user, String id) {
        return orders.find(id).switchIfEmpty(Mono.error(notFound(id)))
                .flatMap(order -> {
                    StoreAccess.readableStore(user, order.storeId());
                    return detail(user, order);
                });
    }

    // --- creating ------------------------------------------------------------

    /**
     * Creates an order in the manager's own store. Every line must pass the "can this be sold?" rule (D30):
     * known product, active (D3), priced (D2), enough stock, quantity of 1 or more. If any line fails,
     * nothing is saved and no stock moves. A repeated request with the same idempotency key returns the
     * order created the first time (a double tap or a network retry creates one order, D8).
     */
    public Mono<OrderDetail> create(UserAccount user, CreateOrderRequest request, String idempotencyKey) {
        if (user.role() != UserAccount.Role.STORE_MANAGER) {
            return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "Only store managers create customer orders"));
        }
        String key = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.strip();
        Mono<OrderDetail> fresh = Mono.defer(() -> createNew(user, request, key));
        if (key == null) {
            return fresh;
        }
        return orders.findByIdempotencyKey(key)
                .flatMap(existing -> sameRequester(existing, user) ? detail(user, existing) : Mono.error(keyReused()))
                .switchIfEmpty(fresh)
                // two copies of the same request at the same moment: the unique key lets one in; the other returns it
                .onErrorResume(DataIntegrityViolationException.class, e -> orders.findByIdempotencyKey(key)
                        .flatMap(existing -> detail(user, existing))
                        .switchIfEmpty(Mono.error(e)));
    }

    private Mono<OrderDetail> createNew(UserAccount user, CreateOrderRequest request, String key) {
        // D14: the same product added twice becomes one line with the quantities added
        Map<String, Integer> quantities = new LinkedHashMap<>();
        for (LineRequest line : request.lines()) {
            quantities.merge(line.sku(), line.qty(), Integer::sum);
        }
        return Flux.fromIterable(quantities.keySet())
                .concatMap(sku -> products.findBySku(sku)
                        .switchIfEmpty(Mono.error(badRequest("Unknown product '" + sku + "'")))
                        .flatMap(OrderService::checkSellable))
                .collectList()
                .flatMap(sellable -> {
                    BigDecimal total = sellable.stream()
                            .map(p -> p.priceInr().multiply(BigDecimal.valueOf(quantities.get(p.sku()))))
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    return orders.nextOrderId().flatMap(id -> orders.insertOrder(id, user.storeId(), user.id(),
                                    request.customerName().strip(), request.customerPhone(), total, key)
                            .thenMany(Flux.fromIterable(sellable).concatMap(p -> {
                                int qty = quantities.get(p.sku());
                                return orders.insertLine(id, p.sku(), qty, p.priceInr())   // price snapshot (D15)
                                        .then(takeStock(user, id, p, qty));
                            }))
                            .then(orders.find(id)));
                })
                .flatMap(order -> detail(user, order))
                .as(transaction::transactional);
    }

    /** D30 points 2 and 3: active and priced. (Point 4, enough stock, is checked atomically when stock is taken.) */
    private static Mono<Product> checkSellable(Product p) {
        if (p.archived()) {
            return Mono.error(conflict("'" + p.sku() + "' is archived and can't be sold"));
        }
        if (p.priceInr() == null) {
            return Mono.error(conflict("'" + p.sku() + "' has no price yet; ask head office (D2)"));
        }
        return Mono.just(p);
    }

    private Mono<Void> takeStock(UserAccount user, String orderId, Product p, int qty) {
        return inventory.addAtomically(user.storeId(), p.sku(), -qty)
                .switchIfEmpty(Mono.defer(() -> inventory.find(user.storeId(), p.sku())
                        .map(item -> item.quantity())
                        .defaultIfEmpty(0)
                        .flatMap(left -> Mono.<Integer>error(conflict("Only " + left + " of '" + p.name() + "' left in stock")))))
                .flatMap(after -> inventory.recordMovement(user.storeId(), p.sku(), -qty, after, "ORDER_CREATED",
                        null, "ORDER", orderId, user.id()))
                .then();
    }

    // --- status --------------------------------------------------------------

    /**
     * Moves an order along its lifecycle. Cancelling (from Pending or Packed only) gives the stock back (D29),
     * in the same transaction. A stale version or a status someone already changed gets 409.
     */
    public Mono<OrderDetail> changeStatus(UserAccount user, String id, String to, long version) {
        return orders.find(id).switchIfEmpty(Mono.error(notFound(id)))
                .flatMap(order -> {
                    StoreAccess.checkCanChangeStock(user, order.storeId());
                    if (!NEXT.getOrDefault(order.status(), Set.of()).contains(to)) {
                        return Mono.error(conflict("An order that is " + order.status().toLowerCase()
                                + " can't be moved to " + to.toLowerCase()));
                    }
                    Mono<Void> stockBack = "CANCELLED".equals(to) ? returnStock(user, order) : Mono.empty();
                    return orders.changeStatus(id, order.status(), to, version)
                            .flatMap(saved -> saved ? stockBack : Mono.error(conflict(
                                    "Order " + id + " was changed by someone else; reload and try again")))
                            .then(orders.find(id));
                })
                .flatMap(order -> detail(user, order))
                .as(transaction::transactional);
    }

    private Mono<Void> returnStock(UserAccount user, OrderRow order) {
        return orders.lines(order.id())
                .filter(line -> line.productSku() != null && line.qty() > 0)
                .concatMap(line -> inventory.addAtomically(order.storeId(), line.productSku(), line.qty())
                        .flatMap(after -> inventory.recordMovement(order.storeId(), line.productSku(), line.qty(), after,
                                "ORDER_CANCELLED", null, "ORDER", order.id(), user.id())))
                .then();
    }

    // --- helpers -------------------------------------------------------------

    private Mono<OrderDetail> detail(UserAccount user, OrderRow o) {
        boolean ownStoreStaff = user.role() == UserAccount.Role.STORE_MANAGER && o.storeId().equals(user.storeId());
        return Mono.zip(orders.lines(o.id()).collectList(), stuckAfter(), orders.userName(o.placedBy()))
                .map(t -> new OrderDetail(o.id(), o.storeId(), o.placedBy(), t.getT3(), o.status(), o.customerName(),
                        ownStoreStaff ? o.customerPhone() : maskPhone(o.customerPhone()),    // D16
                        o.totalAmount(), o.createdAt(), o.reviewStatus(), o.source(), isStuck(o, t.getT2()), o.version(), t.getT1()));
    }

    /** D32: an order still open longer than the remote-config limit (default 7 days) is "stuck". */
    private Mono<Integer> stuckAfter() {
        return settings.integer("orders.stuckAfterDays", 7);
    }

    private static boolean isStuck(OrderRow o, int stuckDays) {
        return OPEN.contains(o.status()) && o.createdAt().isBefore(OffsetDateTime.now().minus(Duration.ofDays(stuckDays)));
    }

    /** "+91 9873819191" becomes "+91 98XXXXX191" (D16). */
    static String maskPhone(String phone) {
        if (phone == null || !phone.matches("\\+91 \\d{10}")) {
            return phone == null ? null : "XXXXXXXXXX";
        }
        String digits = phone.substring(4);
        return "+91 " + digits.substring(0, 2) + "XXXXX" + digits.substring(7);
    }

    private static boolean sameRequester(OrderRow order, UserAccount user) {
        return order.placedBy().equals(user.id());
    }

    private static ResponseStatusException keyReused() {
        return conflict("This idempotency key was already used for a different request");
    }

    private static ResponseStatusException notFound(String id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "No order '" + id + "'");
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
