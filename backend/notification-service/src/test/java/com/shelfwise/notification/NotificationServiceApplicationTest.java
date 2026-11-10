package com.shelfwise.notification;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Smoke test: the service starts and reports itself healthy. */
@SpringBootTest
class NotificationServiceApplicationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void startsAndReportsHealthy() {
        WebTestClient client = WebTestClient.bindToApplicationContext(context).build();

        client.get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP");
    }
}
