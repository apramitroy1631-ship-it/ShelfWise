package com.shelfwise.core.dataimport;

import java.time.Duration;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Imports the provided data when the service starts, if {@code shelfwise.import.enabled=true}
 * (set by docker-compose.yml, so one command gives a working system).
 * Waiting here is fine: it runs once on the start-up thread, before any request is served.
 */
@Component
@ConditionalOnProperty(name = "shelfwise.import.enabled", havingValue = "true")
class ImportOnStartup implements ApplicationRunner {

    private final DataImportService importService;

    ImportOnStartup(DataImportService importService) {
        this.importService = importService;
    }

    @Override
    public void run(ApplicationArguments args) {
        importService.runInsertOnly().block(Duration.ofMinutes(5));
    }
}
