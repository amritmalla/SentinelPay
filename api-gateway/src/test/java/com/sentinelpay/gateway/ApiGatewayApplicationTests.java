package com.sentinelpay.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Baseline test: boots the reactive gateway application context (routing + security + observability).
 * No external dependencies are contacted at context load, so no Testcontainers are required.
 */
@SpringBootTest
class ApiGatewayApplicationTests {

    @Test
    void contextLoads() {
    }
}
