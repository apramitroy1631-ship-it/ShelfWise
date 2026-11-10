package com.shelfwise.core.dataimport;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

import reactor.core.publisher.Mono;

/**
 * Runs an import of the provided data files. Each run is one transaction: if anything fails,
 * nothing from that run is kept, and the failure is recorded on the run.
 * Slice 1: stores and users. Products, stock, orders and purchase orders follow (D1-D32).
 */
@Service
@EnableConfigurationProperties(ImportProperties.class)
public class DataImportService {

    private static final Logger log = LoggerFactory.getLogger(DataImportService.class);

    private final ImportProperties properties;
    private final ImportLog importLog;
    private final StoreAndUserImporter storesAndUsers;
    private final TransactionalOperator transaction;

    public DataImportService(ImportProperties properties, ImportLog importLog, StoreAndUserImporter storesAndUsers,
                             ReactiveTransactionManager transactionManager) {
        this.properties = properties;
        this.importLog = importLog;
        this.storesAndUsers = storesAndUsers;
        this.transaction = TransactionalOperator.create(transactionManager);
    }

    /** Insert-only run (D28): adds what is new, skips what already exists, never overwrites. */
    public Mono<List<FileResult>> runInsertOnly() {
        if (properties.demoPassword() == null || properties.demoPassword().isBlank()) {
            return Mono.error(new IllegalStateException("DEMO_PASSWORD must be set to import users (D25)"));
        }
        if (!Files.isDirectory(properties.dataDir())) {
            return Mono.error(new IllegalStateException("Import data folder not found: " + properties.dataDir().toAbsolutePath()));
        }
        return Mono.zip(
                        CsvFile.read(properties.dataDir().resolve(StoreAndUserImporter.STORES_FILE)),
                        CsvFile.read(properties.dataDir().resolve(StoreAndUserImporter.USERS_FILE)))
                .flatMap(files -> {
                    var stores = files.getT1();
                    var users = files.getT2();
                    return importLog.startRun("INSERT_ONLY").flatMap(runId ->
                            storesAndUsers.importStores(runId, stores)
                                    .zipWith(storesAndUsers.importUsers(runId, users, properties.demoPassword()))
                                    .flatMap(results -> storesAndUsers.linkMainContacts(runId, stores, users)
                                            .thenReturn(List.of(results.getT1(), results.getT2())))
                                    .flatMap(results -> importLog.finishRun(runId, "SUCCEEDED", Map.of("files", results))
                                            .thenReturn(results)));
                })
                .as(transaction::transactional)
                .doOnNext(results -> results.forEach(r -> log.info("Import {}: {} added, {} skipped (already present), {} rejected",
                        r.file(), r.added(), r.skipped(), r.rejected())))
                .doOnError(e -> log.error("Import failed; nothing from this run was kept: {}", e.getMessage()));
    }
}
