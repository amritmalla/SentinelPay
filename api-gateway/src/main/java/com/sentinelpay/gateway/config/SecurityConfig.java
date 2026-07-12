package com.sentinelpay.gateway.config;

import org.springframework.boot.actuate.autoconfigure.security.reactive.EndpointRequest;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.info.InfoEndpoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

import static org.springframework.security.config.Customizer.withDefaults;

/**
 * Reactive security baseline for the edge. Health and info are open for probes; other actuator
 * endpoints require HTTP Basic (credentials via SPRING_SECURITY_USER_* in non-dev). CSRF is disabled
 * for a stateless API.
 *
 * <p>Proxied routes are currently permitted at this shell stage. JWT validation (the platform's
 * authentication boundary) is delivered by the {@code spring-security-auth-review} skill — see the
 * service README's Production Notes. This service is the only internet-facing component.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(ex -> ex
                        .matchers(EndpointRequest.to(HealthEndpoint.class, InfoEndpoint.class)).permitAll()
                        .matchers(EndpointRequest.toAnyEndpoint()).authenticated()
                        .anyExchange().permitAll())
                .httpBasic(withDefaults());
        return http.build();
    }
}
