package com.sentinelpay.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(GatewaySecurityProperties.class)
public class GatewaySecurityAutoConfiguration {

    @Bean
    GatewayIdentityFilter gatewayIdentityFilter(GatewaySecurityProperties properties, ObjectMapper objectMapper) {
        return new GatewayIdentityFilter(properties, objectMapper);
    }
}
