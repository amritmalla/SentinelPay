package com.sentinelpay.gateway.filter;

import com.sentinelpay.common.web.CorrelationConstants;
import io.opentelemetry.api.trace.Span;
import org.slf4j.MDC;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Component
public class CorrelationIdGatewayFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String correlationId = request.getHeaders().getFirst(CorrelationConstants.HEADER_CORRELATION_ID);
        if (!StringUtils.hasText(correlationId)) {
            correlationId = UUID.randomUUID().toString();
        }
        String resolvedCorrelationId = correlationId;

        exchange.getResponse().getHeaders().add(CorrelationConstants.HEADER_CORRELATION_ID, resolvedCorrelationId);
        ServerWebExchange mutated = exchange.mutate()
                .request(builder -> builder.header(CorrelationConstants.HEADER_CORRELATION_ID, resolvedCorrelationId))
                .build();

        MDC.put(CorrelationConstants.MDC_REQUEST_ID, resolvedCorrelationId);
        Span.current().setAttribute(CorrelationConstants.SPAN_CORRELATION_ID, resolvedCorrelationId);

        return chain.filter(mutated).doFinally(signalType -> MDC.remove(CorrelationConstants.MDC_REQUEST_ID));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
