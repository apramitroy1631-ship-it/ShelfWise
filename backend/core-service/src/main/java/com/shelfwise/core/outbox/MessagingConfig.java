package com.shelfwise.core.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.rabbitmq.client.ConnectionFactory;

import reactor.core.scheduler.Schedulers;
import reactor.rabbitmq.RabbitFlux;
import reactor.rabbitmq.Sender;
import reactor.rabbitmq.SenderOptions;

/**
 * The RabbitMQ sender used by the outbox relay. The connection opens on first use, so the core service starts
 * (and keeps selling) even when the broker is down; events simply wait in the outbox until it is back.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MessagingProperties.class)
class MessagingConfig {

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "shelfwise.messaging.relay-enabled", havingValue = "true")
    Sender rabbitSender(MessagingProperties properties) throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setUri(properties.rabbitUri());
        factory.useNio();
        factory.setConnectionTimeout(5_000);
        return RabbitFlux.createSender(new SenderOptions()
                .connectionFactory(factory)
                .resourceManagementScheduler(Schedulers.boundedElastic()));
    }
}
