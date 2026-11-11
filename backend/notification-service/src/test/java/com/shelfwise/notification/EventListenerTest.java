package com.shelfwise.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import tools.jackson.databind.ObjectMapper;

/** End to end through a real RabbitMQ: events are applied once, and a broken message goes to the dead-letter queue. */
@SpringBootTest(properties = "shelfwise.messaging.consumer-enabled=true")
@Import(TestDatabaseConfig.class)
class EventListenerTest {

    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.1-management");
    private static Connection connection;
    private static Channel channel;

    static {
        RABBIT.start();
    }

    @Autowired
    private DatabaseClient db;
    @Autowired
    private ObjectMapper json;

    @DynamicPropertySource
    static void rabbit(DynamicPropertyRegistry registry) {
        registry.add("shelfwise.messaging.rabbit-uri", RABBIT::getAmqpUrl);
    }

    @BeforeAll
    static void open() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setUri(RABBIT.getAmqpUrl());
        connection = factory.newConnection();
        channel = connection.createChannel();
    }

    @AfterAll
    static void close() throws Exception {
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    void anEventPublishedTwiceIsAppliedOnce() throws Exception {
        waitFor(() -> queueExists("notification-service.events"));
        String id = UUID.randomUUID().toString();
        byte[] body = json.writeValueAsBytes(Map.of("id", id, "type", "PurchaseOrderDecided", "aggregateId", "PO-10003",
                "occurredAt", OffsetDateTime.now().toString(),
                "payload", Map.of("poId", "PO-10003", "storeId", "S01", "decision", "APPROVED", "status", "APPROVED",
                        "validApprovals", 1, "approvalsNeeded", 1, "reapproval", false, "reason", "")));

        publish(id, body);
        publish(id, body);                                               // the relay may send an event twice (DL-03)

        waitFor(() -> number("SELECT count(*) FROM notifications.processed_events WHERE event_id = '" + id + "'") == 1
                && number("SELECT count(*) FROM notifications.notifications WHERE ref = 'PO-10003'") == 1);
        Thread.sleep(1_000);                                            // give a wrongly applied repeat time to show up
        assertThat(number("SELECT count(*) FROM notifications.notifications WHERE ref = 'PO-10003'")).isEqualTo(1);
        assertThat(messagesIn("notification-service.events")).isZero();   // both acknowledged
    }

    @Test
    void aMessageThatCantBeHandledGoesToTheDeadLetterQueue() throws Exception {
        waitFor(() -> queueExists("notification-service.events"));

        publish("broken", "this is not JSON".getBytes(StandardCharsets.UTF_8));

        waitFor(() -> messagesIn("notification-service.dead") == 1);
        assertThat(messagesIn("notification-service.events")).isZero();   // the queue isn't blocked
    }

    // --- helpers ---------------------------------------------------------------------

    private static void publish(String messageId, byte[] body) throws Exception {
        channel.basicPublish("shelfwise.events", "PurchaseOrderDecided",
                new AMQP.BasicProperties.Builder().messageId(messageId).contentType("application/json").build(), body);
    }

    private static boolean queueExists(String name) {
        try (Channel probe = connection.createChannel()) {
            probe.queueDeclarePassive(name);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static long messagesIn(String queue) {
        try (Channel probe = connection.createChannel()) {
            return probe.queueDeclarePassive(queue).getMessageCount();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private long number(String sql) {
        return db.sql(sql).map((row, meta) -> ((Number) row.get(0)).longValue()).one().block(Duration.ofSeconds(10));
    }

    private static void waitFor(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("condition not met within 30 s").isLessThan(deadline);
            Thread.sleep(200);
        }
    }
}
