package com.shelfwise.core.dataimport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

import com.shelfwise.core.dataimport.CsvFile.Row;

import reactor.core.publisher.Mono;

/**
 * Runs an import of the provided data files. Each run is one transaction: if anything fails,
 * nothing from that run is kept. Order matters because of references:
 * stores, users, suppliers, products, stock, customer orders, purchase orders.
 */
@Service
@EnableConfigurationProperties(ImportProperties.class)
public class DataImportService {

    private static final Logger log = LoggerFactory.getLogger(DataImportService.class);

    private final ImportProperties properties;
    private final ImportLog importLog;
    private final StoreAndUserImporter storesAndUsers;
    private final CatalogueImporter catalogue;
    private final OrderImporter orders;
    private final PurchaseOrderImporter purchaseOrders;
    private final TransactionalOperator transaction;

    public DataImportService(ImportProperties properties, ImportLog importLog, StoreAndUserImporter storesAndUsers,
                             CatalogueImporter catalogue, OrderImporter orders, PurchaseOrderImporter purchaseOrders,
                             ReactiveTransactionManager transactionManager) {
        this.properties = properties;
        this.importLog = importLog;
        this.storesAndUsers = storesAndUsers;
        this.catalogue = catalogue;
        this.orders = orders;
        this.purchaseOrders = purchaseOrders;
        this.transaction = TransactionalOperator.create(transactionManager);
    }

    /** The files read in one run, exactly as written. */
    private record ImportFiles(List<Row> stores, List<Row> users, List<Row> products, List<Row> inventory) {
    }

    /** Insert-only run (D28): adds what is new, skips what already exists, never overwrites. */
    public Mono<List<FileResult>> runInsertOnly() {
        if (properties.demoPassword() == null || properties.demoPassword().isBlank()) {
            return Mono.error(new IllegalStateException("DEMO_PASSWORD must be set to import users (D25)"));
        }
        Path dir = properties.dataDir();
        if (!Files.isDirectory(dir)) {
            return Mono.error(new IllegalStateException("Import data folder not found: " + dir.toAbsolutePath()));
        }
        return Mono.zip(
                        CsvFile.read(dir.resolve(StoreAndUserImporter.STORES_FILE)),
                        CsvFile.read(dir.resolve(StoreAndUserImporter.USERS_FILE)),
                        CsvFile.read(dir.resolve(CatalogueImporter.PRODUCTS_FILE)),
                        CsvFile.read(dir.resolve(CatalogueImporter.INVENTORY_FILE)))
                .map(t -> new ImportFiles(t.getT1(), t.getT2(), t.getT3(), t.getT4()))
                .flatMap(files -> importLog.startRun("INSERT_ONLY")
                        .flatMap(runId -> runSteps(runId, files)
                                .flatMap(results -> importLog.finishRun(runId, "SUCCEEDED", Map.of("files", results))
                                        .thenReturn(results))))
                .as(transaction::transactional)
                .doOnNext(results -> results.forEach(r -> log.info("Import {}: {} added, {} skipped (already present), {} rejected",
                        r.file(), r.added(), r.skipped(), r.rejected())))
                .doOnError(e -> log.error("Import failed; nothing from this run was kept: {}", e.getMessage()));
    }

    private Mono<List<FileResult>> runSteps(long runId, ImportFiles files) {
        List<FileResult> results = new ArrayList<>();
        SkuResolver skus = CatalogueImporter.resolverFor(files.products());
        return storesAndUsers.importStores(runId, files.stores()).doOnNext(results::add)
                .then(storesAndUsers.importUsers(runId, files.users(), properties.demoPassword()).doOnNext(results::add))
                .then(storesAndUsers.linkMainContacts(runId, files.stores(), files.users()))
                .then(catalogue.importSuppliers(runId, files.products()).doOnNext(results::add))
                .then(catalogue.importProducts(runId, files.products()).doOnNext(results::add))
                .then(catalogue.importInventory(runId, files.inventory(), skus).doOnNext(results::add))
                .then(orders.importOrders(runId, properties.dataDir().resolve(OrderImporter.ORDERS_FILE), skus,
                        CatalogueImporter.pricesByStoredSku(files.products())).doOnNext(results::add))
                .then(purchaseOrders.importPurchaseOrders(runId,
                        properties.dataDir().resolve(PurchaseOrderImporter.PURCHASE_ORDERS_FILE), skus).doOnNext(results::add))
                .then(Mono.fromSupplier(() -> List.copyOf(results)));
    }
}
