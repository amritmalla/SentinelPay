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
                        .matchers(EndpointRequest.to("health", "info", "prometheus")).permitAll()
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
                // CORS must be handled here, not via spring.cloud.gateway.globalcors: the security
                // filter chain runs before gateway routing, and a browser preflight carries no
                // Authorization header, so it would be rejected (401/403) before any gateway-level
                // CORS config applied — surfacing in the browser as "Failed to fetch". Spring
                // Security's CORS filter answers the preflight and short-circuits authorization.
                // The CorsConfigurationSource bean is dev-profile only, so prod behaviour is unchanged.
                .cors(withDefaults())
                .authorizeExchange(ex -> ex
                        .pathMatchers("/api/v1/webhooks/**").permitAll()
                        .pathMatchers(
                                "/api/v1/payments/*/trail",
                                "/api/v1/fraud-assessments/**",
                                "/api/v1/ops/**")
                        .hasAuthority("OPS")
                        // OPS is a superset of MERCHANT for reads: an operator who can already see
                        // the full decision trail (routing rationale, risk internals) must also be
                        // able to list and open the payment it belongs to. The trail matcher above
                        // stays OPS-only, so MERCHANT is still denied there.
                        .pathMatchers("/api/v1/payments/**").hasAnyAuthority("MERCHANT", "OPS")
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
