package com.sentinelpay.gateway.filter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class IdentityPropagationFilter implements GlobalFilter, Ordered {

    private final String gatewaySecret;

    public IdentityPropagationFilter(@Value("${sentinelpay.security.gateway-secret}") String gatewaySecret) {
        this.gatewaySecret = gatewaySecret;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        var requestBuilder = exchange.getRequest().mutate();
        requestBuilder.headers(headers -> {
            headers.remove("X-Merchant-Id");
            headers.remove("X-Auth-Role");
            headers.remove("X-Gateway-Secret");
        });
        requestBuilder.header("X-Gateway-Secret", gatewaySecret);

        return exchange.getPrincipal()
                .filter(JwtAuthenticationToken.class::isInstance)
                .cast(JwtAuthenticationToken.class)
                .map(jwtAuth -> {
                    requestBuilder.header("X-Merchant-Id", jwtAuth.getToken().getSubject());
                    jwtAuth.getAuthorities().stream()
                            .map(GrantedAuthority::getAuthority)
                            .findFirst()
                            .ifPresent(role -> requestBuilder.header("X-Auth-Role", role));
                    return exchange.mutate().request(requestBuilder.build()).build();
                })
                .defaultIfEmpty(exchange.mutate().request(requestBuilder.build()).build())
                .flatMap(chain::filter);
    }

    @Override
    public int getOrder() {
        return -50;
    }
}
