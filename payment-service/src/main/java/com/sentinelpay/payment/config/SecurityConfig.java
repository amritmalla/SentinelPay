package com.sentinelpay.payment.config;

import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.info.InfoEndpoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

import static org.springframework.security.config.Customizer.withDefaults;

/**
 * Baseline security posture. The API Gateway is the authentication boundary (validates JWT and
 * injects identity headers); internal service endpoints trust those headers and are not internet
 * exposed. Health and info are open for probes; all other actuator endpoints require HTTP Basic
 * (credentials via SPRING_SECURITY_USER_* in non-dev). CSRF is disabled for a stateless API.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(EndpointRequest.to(HealthEndpoint.class, InfoEndpoint.class)).permitAll()
                        .requestMatchers(EndpointRequest.toAnyEndpoint()).authenticated()
                        .anyRequest().permitAll())
                .httpBasic(withDefaults());
        return http.build();
    }
}
