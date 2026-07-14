package com.sentinelpay.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

import static org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers.pathMatchers;

@Configuration
@Profile("dev")
public class DevSecurityConfig {

    @Bean
    @Order(-1)
    SecurityWebFilterChain devSecurityFilterChain(ServerHttpSecurity http) {
        http
                .securityMatcher(pathMatchers("/dev/**"))
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(ex -> ex.anyExchange().permitAll());
        return http.build();
    }
}
