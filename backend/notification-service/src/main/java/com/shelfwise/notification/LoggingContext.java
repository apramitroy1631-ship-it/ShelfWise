package com.shelfwise.notification;

import org.slf4j.MDC;
import org.springframework.context.annotation.Configuration;

import io.micrometer.context.ContextRegistry;

/**
 * Reactive code hops between threads, so a plain thread-local log context would lose the request id.
 * With {@code spring.reactor.context-propagation: auto}, Reactor copies registered values from its context
 * into thread-locals around each step; this registers the request id as one of them (into SLF4J's MDC),
 * so it appears on every log line and in the JSON logs.
 */
@Configuration(proxyBeanMethods = false)
public class LoggingContext {

    static {
        ContextRegistry.getInstance().registerThreadLocalAccessor(RequestIdFilter.KEY,
                () -> MDC.get(RequestIdFilter.KEY),
                value -> MDC.put(RequestIdFilter.KEY, value),
                () -> MDC.remove(RequestIdFilter.KEY));
    }
}
