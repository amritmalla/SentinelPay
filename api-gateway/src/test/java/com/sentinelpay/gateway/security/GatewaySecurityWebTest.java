package com.sentinelpay.gateway.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Testcontainers
class GatewaySecurityWebTest {

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("sentinelpay.security.jwt-secret", () -> JwtTestSupport.JWT_SECRET);
        registry.add("sentinelpay.security.gateway-secret", () -> "test-gateway-secret");
    }

    @Autowired
    WebTestClient webTestClient;

    @Test
    void payments_withoutToken_returns401() {
        webTestClient.get()
                .uri("/api/v1/payments")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void fraudAssessments_withMerchantToken_returns403() throws Exception {
        webTestClient.get()
                .uri("/api/v1/fraud-assessments/{id}", UUID.randomUUID())
                .headers(headers -> {
                    try {
                        headers.setBearerAuth(JwtTestSupport.merchantToken(UUID.randomUUID()));
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                })
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void fraudAssessments_withOpsToken_isAllowedAtGateway() throws Exception {
        webTestClient.get()
                .uri("/api/v1/fraud-assessments/{id}", UUID.randomUUID())
                .headers(headers -> {
                    try {
                        headers.setBearerAuth(JwtTestSupport.opsToken());
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                })
                .exchange()
                .expectStatus().value(status -> assertThatNot401Or403(status));
    }

    @Test
    void payments_withMerchantToken_isAllowedAtGateway() throws Exception {
        webTestClient.get()
                .uri("/api/v1/payments")
                .headers(headers -> {
                    try {
                        headers.setBearerAuth(JwtTestSupport.merchantToken(UUID.randomUUID()));
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                })
                .exchange()
                .expectStatus().value(status -> assertThatNot401Or403(status));
    }

    private static void assertThatNot401Or403(int status) {
        if (status == 401 || status == 403) {
            throw new AssertionError("Expected gateway to allow request, got HTTP " + status);
        }
    }
}
