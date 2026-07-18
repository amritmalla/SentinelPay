package com.sentinelpay.payment.support;

import com.sentinelpay.common.security.GatewayHeaders;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

public final class GatewayTestAuth {

    public static final String SECRET = "test-gateway-secret";

    private GatewayTestAuth() {
    }

    public static MockHttpServletRequestBuilder asMerchant(MockHttpServletRequestBuilder builder, UUID merchantId) {
        return builder
                .header(GatewayHeaders.GATEWAY_SECRET, SECRET)
                .header(GatewayHeaders.MERCHANT_ID, merchantId.toString())
                .header(GatewayHeaders.AUTH_ROLE, "MERCHANT");
    }

    public static MockHttpServletRequestBuilder asOps(MockHttpServletRequestBuilder builder) {
        return asOps(builder, UUID.randomUUID());
    }

    /**
     * OPS scoped to a specific merchant. OPS is not cross-merchant: reads are still scoped by the
     * gateway-injected merchant id, so tests that fetch a seeded payment must pass its owner.
     */
    public static MockHttpServletRequestBuilder asOps(MockHttpServletRequestBuilder builder, UUID merchantId) {
        return builder
                .header(GatewayHeaders.GATEWAY_SECRET, SECRET)
                .header(GatewayHeaders.MERCHANT_ID, merchantId.toString())
                .header(GatewayHeaders.AUTH_ROLE, "OPS");
    }

    public static MockHttpServletRequestBuilder withGatewaySecretOnly(MockHttpServletRequestBuilder builder) {
        return builder.header(GatewayHeaders.GATEWAY_SECRET, SECRET);
    }
}
