package com.sentinelpay.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sentinelpay.security")
public class GatewaySecurityProperties {

    private String gatewaySecret = "";

    public String getGatewaySecret() {
        return gatewaySecret;
    }

    public void setGatewaySecret(String gatewaySecret) {
        this.gatewaySecret = gatewaySecret;
    }
}
