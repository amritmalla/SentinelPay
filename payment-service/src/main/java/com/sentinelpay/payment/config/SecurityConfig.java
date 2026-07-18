package com.sentinelpay.payment.config;

import com.sentinelpay.common.security.GatewayIdentityFilter;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.info.InfoEndpoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
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
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/webhooks/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/payments/*/trail").hasAuthority("OPS")
                        .requestMatchers("/api/v1/ops/**").hasAuthority("OPS")
                        // OPS is a superset of MERCHANT for reads (see gateway SecurityConfig); the
                        // trail matcher above remains OPS-only so MERCHANT is still denied there.
                        .requestMatchers("/api/v1/payments/**").hasAnyAuthority("MERCHANT", "OPS")
                        .requestMatchers(EndpointRequest.to("health", "info", "prometheus")).permitAll()
                        .requestMatchers(EndpointRequest.toAnyEndpoint()).authenticated()
                        .anyRequest().denyAll())
                .httpBasic(withDefaults());
        return http.build();
    }
}
