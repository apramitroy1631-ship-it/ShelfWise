package com.shelfwise.core.outbox;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Messaging settings (prefix {@code shelfwise.messaging}). The RabbitMQ address holds a password, so
 * {@link #toString()} never prints it.
 */
@ConfigurationProperties("shelfwise.messaging")
public record MessagingProperties(String rabbitUri, String exchange, boolean relayEnabled, Duration relayInterval, int batchSize) {

    @Override
    public String toString() {
        return "MessagingProperties[exchange=" + exchange + ", relayEnabled=" + relayEnabled + ", relayInterval=" + relayInterval
                + ", batchSize=" + batchSize + "]";
    }
}
