package com.shelfwise.notification;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** A throwaway PostgreSQL for tests; Flyway creates the 'notifications' schema in it. */
@TestConfiguration(proxyBeanMethods = false)
public class TestDatabaseConfig {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:17.6");
    }
}
