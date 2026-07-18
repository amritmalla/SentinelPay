package com.sentinelpay.provider.config;

import com.sentinelpay.common.security.GatewayIdentityFilter;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.info.InfoEndpoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import static org.springframework.security.config.Customizer.withDefaults;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, GatewayIdentityFilter gatewayIdentityFilter)
            throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(gatewayIdentityFilter, UsernamePasswordAuthenticationFilter.class)
                // provider-service exposes no REST surface today -- it participates over gRPC and
                // Kafka. Everything under /api/** stays authenticated and anything else is denied,
                // so adding a controller cannot accidentally publish an unauthenticated endpoint.
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(EndpointRequest.to("health", "info", "prometheus")).permitAll()
                        .requestMatchers(EndpointRequest.toAnyEndpoint()).authenticated()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .httpBasic(withDefaults());
        return http.build();
    }
}
