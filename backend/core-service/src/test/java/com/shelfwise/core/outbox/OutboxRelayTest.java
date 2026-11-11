package com.shelfwise.core.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import com.shelfwise.core.TestDatabaseConfig;

import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The outbox relay against a real RabbitMQ (M21): confirmed publishing, markers turned into current counts,
 * the database trigger that writes stock-health markers, and two relays running at once.
 */
@SpringBootTest(properties = "shelfwise.messaging.relay-enabled=true")
@Import(TestDatabaseConfig.class)
class OutboxRelayTest {

    private static final String EXCHANGE = "shelfwise.events";
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.1-management");
    private static Connection connection;
    private static Channel channel;
    private static String queue;

    static {
        RABBIT.start();                                 // before both the Spring context and @BeforeAll need its address
    }

    @Autowired
    private OutboxRelay relay;
    @Autowired
    private Outbox outbox;
    @Autowired
    private DatabaseClient db;
    @Autowired
    private ObjectMapper json;

    @DynamicPropertySource
    static void rabbit(DynamicPropertyRegistry registry) {
        registry.add("shelfwise.messaging.rabbit-uri", RABBIT::getAmqpUrl);
    }

    @BeforeAll
    static void listen() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setUri(RABBIT.getAmqpUrl());
        connection = factory.newConnection();
        channel = connection.createChannel();
        channel.exchangeDeclare(EXCHANGE, "topic", true);
        queue = channel.queueDeclare().getQueue();
        channel.queueBind(queue, EXCHANGE, "#");
    }

    @AfterAll
    static void close() throws Exception {
        if (connection != null) {
            connection.close();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        relay.stop();                                   // the tests drive the relay by hand
        run("TRUNCATE outbox_events, stock_movements, inventory, products, suppliers, refresh_tokens, users, stores CASCADE");
        channel.queuePurge(queue);
        run("INSERT INTO stores (id, name, city, state, opened_on) VALUES ('S01','Whitefield','Bengaluru','Karnataka','2019-03-15')");
        run("INSERT INTO suppliers (id, name) VALUES ('SUP23','Supplier SUP23')");
        run("""
            INSERT INTO products (sku, name, category, price_inr, cost_price_inr, gst_rate, supplier_id, status, reorder_level, catalogued_on, source) VALUES
              ('SW-SNK-00091','Smart Buy Potato Chips 150g','Snacks',25,20,12,'SUP23','ACTIVE',25,'2025-12-02','IMPORT'),
              ('SW-GRO-00055','Home Basics Iodised Salt 1kg','Grocery',31,25,0,'SUP23','ACTIVE',10,'2025-01-01','IMPORT'),
              ('SW-SNK-00064','Fresh Basket Chocolate Bar 100g','Snacks',52,42,12,'SUP23','ARCHIVED',25,'2025-04-20','IMPORT')
            """);
        run("""
            INSERT INTO inventory (store_id, sku, quantity, reorder_level) VALUES
              ('S01','SW-SNK-00091',37,25), ('S01','SW-GRO-00055',30,10), ('S01','SW-SNK-00064',0,25)
            """);
    }

    @Test
    void anEventIsPublishedWithConfirmationAndMarkedPublished() throws Exception {
        outbox.add("PURCHASE_ORDER", "PO-10001", "PurchaseOrderDecided", Map.of("poId", "PO-10001", "decision", "APPROVED"))
                .block(Duration.ofSeconds(10));

        assertThat(relay.publishBatch().block(Duration.ofSeconds(30))).isEqualTo(1);

        JsonNode message = next();
        assertThat(message.path("type").asString()).isEqualTo("PurchaseOrderDecided");
        assertThat(message.path("aggregateId").asString()).isEqualTo("PO-10001");
        assertThat(message.path("payload").path("decision").asString()).isEqualTo("APPROVED");
        assertThat(number("SELECT count(*) FROM outbox_events WHERE published_at IS NULL")).isZero();
        assertThat(relay.publishBatch().block(Duration.ofSeconds(30))).isZero();       // nothing sent twice
        assertThat(channel.basicGet(queue, true)).isNull();
    }

    /** The trigger writes a marker only when an item crosses a line (OK / low / out of stock). */
    @Test
    void theDatabaseMarksStockHealthChangesOnlyWhenAnItemCrossesALine() {
        run("UPDATE inventory SET quantity = 30 WHERE sku = 'SW-SNK-00091'");           // 37 -> 30: still OK
        assertThat(number("SELECT count(*) FROM outbox_events")).isZero();

        run("UPDATE inventory SET quantity = 20 WHERE sku = 'SW-SNK-00091'");           // OK -> low
        run("UPDATE inventory SET quantity = 0 WHERE sku = 'SW-GRO-00055'");            // OK -> out
        run("UPDATE inventory SET quantity = 15 WHERE sku = 'SW-SNK-00091'");           // low -> low: nothing
        assertThat(number("SELECT count(*) FROM outbox_events WHERE event_type = 'StockHealthChanged'")).isEqualTo(2);
    }

    /** Many markers for one store become ONE message with the store's current counts (archived items left out, D3). */
    @Test
    void markersBecomeOneMessageWithTheCurrentCounts_D7() throws Exception {
        run("UPDATE inventory SET quantity = 20 WHERE sku = 'SW-SNK-00091'");
        run("UPDATE inventory SET quantity = 0 WHERE sku = 'SW-GRO-00055'");

        relay.publishBatch().block(Duration.ofSeconds(30));

        JsonNode message = next();
        assertThat(message.path("type").asString()).isEqualTo("StockHealthChanged");
        assertThat(message.path("payload").path("outOfStock").path("count").asInt()).isEqualTo(1);
        assertThat(message.path("payload").path("outOfStock").path("latest").asString()).isEqualTo("Home Basics Iodised Salt 1kg");
        assertThat(message.path("payload").path("runningLow").path("count").asInt()).isEqualTo(1);
        assertThat(message.path("payload").path("asOf").asString()).isNotBlank();
        assertThat(channel.basicGet(queue, true)).isNull();                               // only one message for S01
        assertThat(number("SELECT count(*) FROM outbox_events WHERE published_at IS NULL")).isZero();
    }

    @Test
    void startingStateSendsOneSnapshotPerStoreAndOneForFinance() throws Exception {
        relay.markStartingState().block(Duration.ofSeconds(10));
        relay.publishBatch().block(Duration.ofSeconds(30));

        List<String> types = new ArrayList<>();
        for (GetResponse r; (r = channel.basicGet(queue, true)) != null; ) {
            types.add(json.readTree(new String(r.getBody(), StandardCharsets.UTF_8)).path("type").asString());
        }
        assertThat(types).containsExactlyInAnyOrder("StockHealthChanged", "ApprovalQueueChanged");
    }

    /** Two copies of the core service relaying at the same moment: every event goes out once (SKIP LOCKED). */
    @Test
    void twoRelaysAtOnceNeverPublishAnEventTwice() throws Exception {
        for (int i = 0; i < 40; i++) {
            outbox.add("PURCHASE_ORDER", "PO-" + i, "PurchaseOrderReceived", Map.of("n", i)).block(Duration.ofSeconds(10));
        }

        Mono.zip(relay.publishBatch(), relay.publishBatch()).block(Duration.ofSeconds(60));
        relay.publishBatch().block(Duration.ofSeconds(30));                             // anything left over

        List<String> ids = new ArrayList<>();
        for (GetResponse r; (r = channel.basicGet(queue, true)) != null; ) {
            ids.add(json.readTree(new String(r.getBody(), StandardCharsets.UTF_8)).path("id").asString());
        }
        assertThat(ids).hasSize(40).doesNotHaveDuplicates();
    }

    // --- helpers -----------------------------------------------------------------

    private JsonNode next() throws Exception {
        GetResponse response = channel.basicGet(queue, true);
        assertThat(response).as("a message on the exchange").isNotNull();
        return json.readTree(new String(response.getBody(), StandardCharsets.UTF_8));
    }

    private long number(String sql) {
        return db.sql(sql).map((row, meta) -> ((Number) row.get(0)).longValue()).one().block(Duration.ofSeconds(10));
    }

    private void run(String sql) {
        db.sql(sql).then().block(Duration.ofSeconds(10));
    }
}
