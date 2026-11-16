package com.shelfwise.core.observability;

import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.util.context.Context;

/**
 * Gives every request an id (M28), so one call can be followed through the logs of BOTH services:
 * <ul>
 *   <li>A caller may send {@code X-Request-Id} (e.g. a gateway); otherwise a new one is made. It is always returned
 *       in the response header, so a user reporting a problem can quote it.</li>
 *   <li>It sits in the Reactor context, so every log line written while handling the request carries it (MDC, see
 *       {@link LoggingContext}), and the outbox stores it with each event, so the notification service logs the
 *       same id when it handles that event.</li>
 *   <li>One access line per request: method, path, status, time. The query string is left out on purpose: searches
 *       carry customer names (D16).</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter implements WebFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String KEY = "requestId";

    /** Accept only short, plain ids from callers, so a header can't inject text into the logs. */
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{8,64}");
    private static final Logger log = LoggerFactory.getLogger("http");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(HEADER);
        String id = incoming != null && SAFE.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();
        exchange.getResponse().getHeaders().set(HEADER, id);
        long started = System.nanoTime();
        return chain.filter(exchange)
                .doFinally(signal -> logRequest(exchange, id, signal, (System.nanoTime() - started) / 1_000_000))
                .contextWrite(Context.of(KEY, id));
    }

    private static void logRequest(ServerWebExchange exchange, String id, SignalType signal, long millis) {
        String path = exchange.getRequest().getPath().value();
        HttpStatusCode set = exchange.getResponse().getStatusCode();
        // a handler that returns normally leaves the status unset: that is the default 200
        String status = set != null ? String.valueOf(set.value()) : signal == SignalType.CANCEL ? "cancelled" : "200";
        MDC.put(KEY, id);
        try {
            if (path.startsWith("/actuator/health")) {
                log.debug("{} {} {} {} ms", exchange.getRequest().getMethod(), path, status, millis);
            } else {
                log.info("{} {} {} {} ms", exchange.getRequest().getMethod(), path, status, millis);
            }
        } finally {
            MDC.remove(KEY);
        }
    }
}
