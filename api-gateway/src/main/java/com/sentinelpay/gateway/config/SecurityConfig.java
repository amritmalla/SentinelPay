package com.sentinelpay.gateway.config;

import com.sentinelpay.gateway.security.RoleClaimConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.security.reactive.EndpointRequest;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.info.InfoEndpoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;

import static org.springframework.security.config.Customizer.withDefaults;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    ReactiveUserDetailsService actuatorUserDetailsService(
            @Value("${spring.security.user.name:actuator}") String username,
            @Value("${spring.security.user.password:actuator}") String password) {
        UserDetails user = User.withUsername(username)
                .password("{noop}" + password)
                .roles("ACTUATOR")
                .build();
        return new MapReactiveUserDetailsService(user);
    }

    @Bean
    @Order(0)
    SecurityWebFilterChain actuatorSecurityFilterChain(ServerHttpSecurity http) {
        http
                .securityMatcher(EndpointRequest.toAnyEndpoint())
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(ex -> ex
                        .matchers(EndpointRequest.to(HealthEndpoint.class, InfoEndpoint.class)).permitAll()
                        .anyExchange().authenticated())
                .httpBasic(withDefaults());
        return http.build();
    }

    @Bean
    @Order(1)
    SecurityWebFilterChain apiSecurityFilterChain(
            ServerHttpSecurity http,
            RoleClaimConverter roleClaimConverter) {
        ReactiveJwtAuthenticationConverterAdapter jwtConverter =
                new ReactiveJwtAuthenticationConverterAdapter(jwtAuthenticationConverter(roleClaimConverter));

        http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(ex -> ex
                        .pathMatchers("/api/v1/webhooks/**").permitAll()
                        .pathMatchers(
                                "/api/v1/payments/*/trail",
                                "/api/v1/fraud-assessments/**",
                                "/api/v1/providers/**",
                                "/api/v1/reconciliation-runs/**")
                        .hasAuthority("OPS")
                        .pathMatchers("/api/v1/payments/**").hasAuthority("MERCHANT")
                        .anyExchange().denyAll())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(jwtConverter)));
        return http.build();
    }

    private static JwtAuthenticationConverter jwtAuthenticationConverter(RoleClaimConverter roleClaimConverter) {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(roleClaimConverter);
        return converter;
    }
}
