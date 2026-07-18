package com.sentinelpay.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.List;

import static org.springframework.security.config.Customizer.withDefaults;
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
                .cors(withDefaults())
                .authorizeExchange(ex -> ex.anyExchange().permitAll());
        return http.build();
    }

    /**
     * Dev-only CORS for the dashboard SPA, applied inside the security filter chain.
     *
     * <p>This cannot live in {@code spring.cloud.gateway.globalcors}: that only covers
     * gateway-routed requests (so it never applies to the gateway's own {@code /dev/token}
     * controller), and it is evaluated after Spring Security. A browser preflight carries no
     * {@code Authorization} header, so it was rejected with 401/403 before any CORS headers were
     * written — which the browser surfaces as "Failed to fetch".
     *
     * <p>No {@code allowCredentials}: the SPA authenticates with a bearer token in the
     * Authorization header, not cookies, so credentialed CORS is unnecessary.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${sentinelpay.gateway.cors.allowed-origins:http://localhost:5173}")
            List<String> allowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
