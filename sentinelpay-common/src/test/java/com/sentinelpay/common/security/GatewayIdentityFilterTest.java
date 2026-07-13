package com.sentinelpay.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayIdentityFilterTest {

    private static final String SECRET = "test-gateway-secret";

    private GatewayIdentityFilter filter;
    private MockHttpServletResponse response;
    private AtomicReference<Authentication> capturedAuth;

    @BeforeEach
    void setUp() {
        GatewaySecurityProperties properties = new GatewaySecurityProperties();
        properties.setGatewaySecret(SECRET);
        filter = new GatewayIdentityFilter(properties, new ObjectMapper());
        response = new MockHttpServletResponse();
        capturedAuth = new AtomicReference<>();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validSecretAndIdentity_populatesAuthentication() throws ServletException, java.io.IOException {
        UUID merchantId = UUID.randomUUID();
        MockHttpServletRequest request = apiRequest();
        request.addHeader(GatewayHeaders.GATEWAY_SECRET, SECRET);
        request.addHeader(GatewayHeaders.MERCHANT_ID, merchantId.toString());
        request.addHeader(GatewayHeaders.AUTH_ROLE, "MERCHANT");

        filter.doFilter(request, response, captureAuthChain());

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(capturedAuth.get()).isInstanceOf(PreAuthenticatedAuthenticationToken.class);
        assertThat(capturedAuth.get().getPrincipal()).isEqualTo(merchantId.toString());
        assertThat(capturedAuth.get().getAuthorities()).extracting("authority").containsExactly("MERCHANT");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void wrongSecret_returns401() throws ServletException, java.io.IOException {
        MockHttpServletRequest request = apiRequest();
        request.addHeader(GatewayHeaders.GATEWAY_SECRET, "wrong");

        filter.doFilter(request, response, captureAuthChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(capturedAuth.get()).isNull();
    }

    @Test
    void secretOnly_webhookStyle_passesWithoutAuthentication() throws ServletException, java.io.IOException {
        MockHttpServletRequest request = apiRequest();
        request.addHeader(GatewayHeaders.GATEWAY_SECRET, SECRET);

        filter.doFilter(request, response, captureAuthChain());

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(capturedAuth.get()).isNull();
    }

    @Test
    void nonApiPath_skipsFilter() throws ServletException, java.io.IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");

        filter.doFilter(request, response, captureAuthChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    private MockFilterChain captureAuthChain() {
        return new MockFilterChain() {
            @Override
            public void doFilter(
                    jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
                capturedAuth.set(SecurityContextHolder.getContext().getAuthentication());
            }
        };
    }

    private static MockHttpServletRequest apiRequest() {
        return new MockHttpServletRequest("GET", "/api/v1/payments");
    }
}
