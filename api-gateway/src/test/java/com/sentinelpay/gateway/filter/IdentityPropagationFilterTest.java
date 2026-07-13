package com.sentinelpay.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class IdentityPropagationFilterTest {

    @Test
    void stripsSpoofedHeadersAndInjectsTrustedIdentity() {
        IdentityPropagationFilter filter = new IdentityPropagationFilter("gateway-secret");
        AtomicReference<ServerHttpRequest> forwarded = new AtomicReference<>();

        GatewayFilterChain chain = exchange -> {
            forwarded.set(exchange.getRequest());
            return Mono.empty();
        };

        MockServerHttpRequest request = MockServerHttpRequest.post("/api/v1/payments/charge")
                .header("X-Merchant-Id", "spoofed-merchant")
                .header("X-Auth-Role", "OPS")
                .header("X-Gateway-Secret", "client-secret")
                .build();

        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject("trusted-merchant")
                .claim("role", "MERCHANT")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        JwtAuthenticationToken principal = new JwtAuthenticationToken(
                jwt, List.of(new SimpleGrantedAuthority("MERCHANT")));

        ServerWebExchange exchange = MockServerWebExchange.from(request)
                .mutate()
                .principal(Mono.just(principal))
                .build();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        HttpHeaders headers = forwarded.get().getHeaders();
        assertThat(headers.getFirst("X-Merchant-Id")).isEqualTo("trusted-merchant");
        assertThat(headers.getFirst("X-Auth-Role")).isEqualTo("MERCHANT");
        assertThat(headers.getFirst("X-Gateway-Secret")).isEqualTo("gateway-secret");
    }
}
