package com.sentinelpay.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.List;

/**
 * Token-bucket-style fixed-window rate limiter backed by Redis ({@code ratelimit:{principal}}).
 *
 * <p>When Redis is unavailable the filter <strong>fail-opens</strong> (allows the request and logs a warning),
 * per data-architecture.md — unlike Spring Cloud Gateway's built-in {@code RequestRateLimiter}, which denies on
 * Redis errors.
 */
@Component
public class FailOpenRateLimitFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(FailOpenRateLimitFilter.class);
    private static final String KEY_PREFIX = "ratelimit:";
    private static final int WINDOW_SECONDS = 60;

    private final ReactiveStringRedisTemplate redis;
    private final boolean enabled;
    private final int requestsPerMinute;

    public FailOpenRateLimitFilter(
            ReactiveStringRedisTemplate redis,
            @Value("${sentinelpay.gateway.rate-limit.enabled:true}") boolean enabled,
            @Value("${sentinelpay.gateway.rate-limit.requests-per-minute:120}") int requestsPerMinute) {
        this.redis = redis;
        this.enabled = enabled;
        this.requestsPerMinute = requestsPerMinute;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!enabled) {
            return chain.filter(exchange);
        }

        String principal = resolvePrincipal(exchange);
        String key = KEY_PREFIX + principal;

        return redis.execute(
                        RateLimitScripts.FIXED_WINDOW_ALLOW,
                        List.of(key),
                        List.of(String.valueOf(requestsPerMinute), String.valueOf(WINDOW_SECONDS)))
                .next()
                .map(result -> result != null && result == 1L)
                .defaultIfEmpty(true)
                .onErrorResume(ex -> {
                    log.warn(
                            "Redis rate-limit check failed for principal {} — fail-open (allowing request)",
                            principal,
                            ex);
                    return Mono.just(true);
                })
                .flatMap(allowed -> {
                    if (allowed) {
                        return chain.filter(exchange);
                    }
                    exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                    return exchange.getResponse().setComplete();
                });
    }

    static String resolvePrincipal(ServerWebExchange exchange) {
        String merchantId = exchange.getRequest().getHeaders().getFirst("X-Merchant-Id");
        if (merchantId != null && !merchantId.isBlank()) {
            return merchantId.trim();
        }
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        if (remote != null && remote.getAddress() != null) {
            return remote.getAddress().getHostAddress();
        }
        return "anonymous";
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
