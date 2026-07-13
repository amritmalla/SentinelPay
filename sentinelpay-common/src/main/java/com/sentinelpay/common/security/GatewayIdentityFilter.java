package com.sentinelpay.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelpay.common.error.ApiError;
import com.sentinelpay.common.error.ErrorCode;
import com.sentinelpay.common.error.ErrorEnvelope;
import com.sentinelpay.common.web.CorrelationConstants;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Trust boundary for internal services: requires the gateway shared secret on {@code /api/**} and
 * establishes Spring Security authentication from gateway-injected identity headers.
 */
public class GatewayIdentityFilter extends OncePerRequestFilter {

    private final GatewaySecurityProperties properties;
    private final ObjectMapper objectMapper;

    public GatewayIdentityFilter(GatewaySecurityProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String secret = request.getHeader(GatewayHeaders.GATEWAY_SECRET);
        if (!constantTimeEquals(secret, properties.getGatewaySecret())) {
            writeUnauthorized(response);
            return;
        }

        String merchantId = request.getHeader(GatewayHeaders.MERCHANT_ID);
        String role = request.getHeader(GatewayHeaders.AUTH_ROLE);
        if (merchantId != null && !merchantId.isBlank()) {
            var authorities = role == null || role.isBlank()
                    ? List.<SimpleGrantedAuthority>of()
                    : List.of(new SimpleGrantedAuthority(role));
            var auth = new PreAuthenticatedAuthenticationToken(merchantId.trim(), null, authorities);
            SecurityContextHolder.getContext().setAuthentication(auth);
        }

        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        String requestId = MDC.get(CorrelationConstants.MDC_REQUEST_ID);
        ApiError error = new ApiError(
                ErrorCode.UNAUTHENTICATED.name(),
                "Gateway authentication required",
                null,
                requestId);
        response.setStatus(ErrorCode.UNAUTHENTICATED.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ErrorEnvelope.of(error));
    }

    static boolean constantTimeEquals(String actual, String expected) {
        if (actual == null || expected == null) {
            return actual == expected;
        }
        return MessageDigest.isEqual(
                actual.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }
}
