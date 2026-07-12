package com.sentinelpay.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;

class FailOpenRateLimitFilterTest {

    @Test
    void resolvePrincipal_prefersMerchantHeader() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/payments/charge")
                        .header("X-Merchant-Id", "merchant-42")
                        .build());

        assertThat(FailOpenRateLimitFilter.resolvePrincipal(exchange)).isEqualTo("merchant-42");
    }

    @Test
    void resolvePrincipal_fallsBackToRemoteAddress() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/payments/charge")
                        .remoteAddress(new java.net.InetSocketAddress("203.0.113.10", 1234))
                        .build());

        assertThat(FailOpenRateLimitFilter.resolvePrincipal(exchange)).isEqualTo("203.0.113.10");
    }
}
