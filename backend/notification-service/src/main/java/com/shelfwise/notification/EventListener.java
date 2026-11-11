package com.shelfwise.notification;

import java.time.Duration;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Component;

import com.rabbitmq.client.ConnectionFactory;

import jakarta.annotation.PreDestroy;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.rabbitmq.AcknowledgableDelivery;
import reactor.rabbitmq.BindingSpecification;
import reactor.rabbitmq.ConsumeOptions;
import reactor.rabbitmq.ExchangeSpecification;
import reactor.rabbitmq.QueueSpecification;
import reactor.rabbitmq.RabbitFlux;
import reactor.rabbitmq.Receiver;
import reactor.rabbitmq.ReceiverOptions;
import reactor.rabbitmq.Sender;
import reactor.rabbitmq.SenderOptions;
import reactor.util.retry.Retry;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes the core service's events from RabbitMQ (DL-03) and passes each to the {@link NotificationHandler}.
 * <ul>
 *   <li>A message is acknowledged only after it was handled and saved; if this service stops half-way, RabbitMQ
 *       delivers it again (the handler skips repeats).</li>
 *   <li>A message that still fails after 3 quick retries goes to the dead-letter queue (for a person to look at)
 *       instead of blocking everything behind it.</li>
 *   <li>If the broker is down, the service keeps serving the bell and reconnects with growing pauses (up to 30 s).</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "shelfwise.messaging.consumer-enabled", havingValue = "true")
public class EventListener {

    private static final Logger log = LoggerFactory.getLogger(EventListener.class);

    private final NotificationHandler handler;
    private final ObjectMapper json;
    private final String exchange;
    private final String queue;
    private final String deadLetterExchange;
    private final String deadLetterQueue;
    private final Sender sender;
    private final Receiver receiver;
    private volatile Disposable loop;

    public EventListener(NotificationHandler handler, ObjectMapper json,
                         @Value("${shelfwise.messaging.rabbit-uri}") String rabbitUri,
                         @Value("${shelfwise.messaging.exchange}") String exchange,
                         @Value("${shelfwise.messaging.queue}") String queue,
                         @Value("${shelfwise.messaging.dead-letter-exchange}") String deadLetterExchange,
                         @Value("${shelfwise.messaging.dead-letter-queue}") String deadLetterQueue) throws Exception {
        this.handler = handler;
        this.json = json;
        this.exchange = exchange;
        this.queue = queue;
        this.deadLetterExchange = deadLetterExchange;
        this.deadLetterQueue = deadLetterQueue;
        ConnectionFactory factory = new ConnectionFactory();
        factory.setUri(rabbitUri);
        factory.useNio();
        factory.setConnectionTimeout(5_000);
        this.sender = RabbitFlux.createSender(new SenderOptions().connectionFactory(factory)
                .resourceManagementScheduler(Schedulers.boundedElastic()));
        this.receiver = RabbitFlux.createReceiver(new ReceiverOptions().connectionFactory(factory)
                .connectionSubscriptionScheduler(Schedulers.boundedElastic()));
    }

    @org.springframework.context.event.EventListener(ApplicationReadyEvent.class)
    public void start() {
        loop = declareQueues()
                .thenMany(Flux.defer(() -> receiver.consumeManualAck(queue, new ConsumeOptions().qos(20))))
                .concatMap(this::process)
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(2)).maxBackoff(Duration.ofSeconds(30))
                        .doBeforeRetry(signal -> log.warn("Can't read events from RabbitMQ yet (attempt {}): {}",
                                signal.totalRetries() + 1, signal.failure().toString())))
                .subscribe();
    }

    @PreDestroy
    public void stop() {
        if (loop != null) {
            loop.dispose();
        }
        receiver.close();
        sender.close();
    }

    /** Declaring is idempotent: the same names and settings every time, by both services. */
    private Mono<Void> declareQueues() {
        return sender.declareExchange(ExchangeSpecification.exchange(exchange).type("topic").durable(true))
                .then(sender.declareExchange(ExchangeSpecification.exchange(deadLetterExchange).type("fanout").durable(true)))
                .then(sender.declareQueue(QueueSpecification.queue(deadLetterQueue).durable(true)))
                .then(sender.bindQueue(BindingSpecification.binding(deadLetterExchange, "", deadLetterQueue)))
                .then(sender.declareQueue(QueueSpecification.queue(queue).durable(true)
                        .arguments(Map.of("x-dead-letter-exchange", deadLetterExchange))))
                .then(sender.bindQueue(BindingSpecification.binding(exchange, "#", queue)))
                .doOnSuccess(ok -> log.info("Listening for events on queue {}", queue))
                .then();
    }

    private Mono<Void> process(AcknowledgableDelivery delivery) {
        String eventId = delivery.getProperties().getMessageId();
        return Mono.fromCallable(() -> json.readTree(delivery.getBody()))
                .flatMap(handler::handle)
                .retryWhen(Retry.backoff(3, Duration.ofMillis(500)))
                .doOnNext(changed -> delivery.ack())
                .onErrorResume(e -> {
                    log.error("Event {} could not be handled; moved to {}: {}", eventId, deadLetterQueue, e.toString());
                    delivery.nack(false);
                    return Mono.empty();
                })
                .then();
    }
}
