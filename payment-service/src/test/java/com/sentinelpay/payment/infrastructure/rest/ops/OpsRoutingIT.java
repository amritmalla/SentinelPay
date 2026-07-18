package com.sentinelpay.payment.infrastructure.rest.ops;

import com.sentinelpay.common.error.GlobalExceptionHandler;
import com.sentinelpay.common.security.GatewaySecurityAutoConfiguration;
import com.sentinelpay.payment.PaymentTestContainers;
import com.sentinelpay.payment.application.port.ProviderBanditStore;
import com.sentinelpay.payment.application.port.ProviderCircuitBreakers;
import com.sentinelpay.payment.application.port.ProviderHealthStore;
import com.sentinelpay.payment.config.SecurityConfig;
import com.sentinelpay.payment.domain.Provider;
import com.sentinelpay.payment.support.GatewayTestAuth;
import com.sentinelpay.payment.support.OpenApiContractSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static com.sentinelpay.payment.support.GatewayTestAuth.asMerchant;
import static com.sentinelpay.payment.support.GatewayTestAuth.asOps;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
@AutoConfigureMockMvc
@Import({GlobalExceptionHandler.class, SecurityConfig.class, GatewaySecurityAutoConfiguration.class})
class OpsRoutingIT {

    @Autowired
    MockMvc mockMvc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PaymentTestContainers.register(registry);
        registry.add("sentinelpay.security.gateway-secret", () -> GatewayTestAuth.SECRET);
    }

    @Test
    void providers_withoutAuth_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/ops/routing/providers"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void providers_withMerchantRole_returns403() throws Exception {
        mockMvc.perform(asMerchant(get("/api/v1/ops/routing/providers"), UUID.randomUUID()))
                .andExpect(status().isForbidden());
    }

    @Test
    void providers_withOpsRole_returns200() throws Exception {
        mockMvc.perform(asOps(get("/api/v1/ops/routing/providers")))
                .andExpect(status().isOk())
                .andExpect(OpenApiContractSupport.openApi())
                .andExpect(jsonPath("$.providers").isArray())
                .andExpect(jsonPath("$.providers.length()").value(2))
                .andExpect(jsonPath("$.degraded").isBoolean());
    }

    @Test
    void config_withOpsRole_returns200() throws Exception {
        mockMvc.perform(asOps(get("/api/v1/ops/routing/config")))
                .andExpect(status().isOk())
                .andExpect(OpenApiContractSupport.openApi())
                .andExpect(jsonPath("$.policy").isString())
                .andExpect(jsonPath("$.breaker.enabled").isBoolean());
    }

    @Test
    void config_withMerchantRole_returns403() throws Exception {
        mockMvc.perform(asMerchant(get("/api/v1/ops/routing/config"), UUID.randomUUID()))
                .andExpect(status().isForbidden());
    }

    @SpringBootTest(properties = {
            "spring.task.scheduling.enabled=false",
            "spring.main.allow-bean-definition-overriding=true"
    })
    @AutoConfigureMockMvc
    @Import({
            GlobalExceptionHandler.class,
            SecurityConfig.class,
            GatewaySecurityAutoConfiguration.class,
            DegradedResponseIT.DegradedStores.class
    })
    static class DegradedResponseIT {

        @Autowired
        MockMvc mockMvc;

        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry registry) {
            PaymentTestContainers.register(registry);
            registry.add("sentinelpay.security.gateway-secret", () -> GatewayTestAuth.SECRET);
        }

        @Test
        void providers_whenStoresDegraded_returns200WithDegradedFlag() throws Exception {
            mockMvc.perform(asOps(get("/api/v1/ops/routing/providers")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.degraded").value(true))
                    .andExpect(jsonPath("$.providers[0].health.degraded").value(true))
                    .andExpect(jsonPath("$.providers[0].bandit.degraded").value(true));
        }

        @TestConfiguration
        static class DegradedStores {

            @Bean
            @Primary
            ProviderHealthStore degradedHealthStore() {
                return new ProviderHealthStore() {
                    @Override
                    public void recordOutcome(Provider provider, boolean success, long latencyMs) {
                        // no-op
                    }

                    @Override
                    public ProviderHealthView read(Provider provider) {
                        return ProviderHealthView.degradedNeutral();
                    }
                };
            }

            @Bean
            @Primary
            ProviderBanditStore degradedBanditStore() {
                return new ProviderBanditStore() {
                    @Override
                    public Posterior read(Provider provider) {
                        return Posterior.degradedPrior();
                    }

                    @Override
                    public void recordOutcome(Provider provider, boolean success) {
                        // no-op
                    }
                };
            }

            @Bean
            @Primary
            ProviderCircuitBreakers noopBreakers() {
                return new ProviderCircuitBreakers() {
                    @Override
                    public String stateName(Provider provider) {
                        return "CLOSED";
                    }

                    @Override
                    public void recordOutcome(Provider provider, boolean success, long elapsedMs) {
                        // no-op
                    }
                };
            }
        }
    }
}
