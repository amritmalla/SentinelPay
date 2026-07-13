package com.sentinelpay.common.security;

public final class GatewayHeaders {

    public static final String GATEWAY_SECRET = "X-Gateway-Secret";
    public static final String MERCHANT_ID = "X-Merchant-Id";
    public static final String AUTH_ROLE = "X-Auth-Role";

    private GatewayHeaders() {
    }
}
