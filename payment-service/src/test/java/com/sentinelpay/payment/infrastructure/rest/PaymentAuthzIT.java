package com.sentinelpay.payment.infrastructure.rest;

import com.sentinelpay.common.error.GlobalExceptionHandler;
import com.sentinelpay.common.security.GatewaySecurityAutoConfiguration;
import com.sentinelpay.payment.PaymentTestContainers;
import com.sentinelpay.payment.application.port.RiskEvaluator;
import com.sentinelpay.payment.config.SecurityConfig;
import com.sentinelpay.payment.domain.PaymentStatus;
import com.sentinelpay.payment.infrastructure.persistence.PaymentEntity;
import com.sentinelpay.payment.infrastructure.persistence.PaymentRepository;
import com.sentinelpay.payment.infrastructure.persistence.PaymentStatusHistoryRepository;
import com.sentinelpay.payment.infrastructure.risk.RiskAssessmentClient;
import com.sentinelpay.payment.support.GatewayTestAuth;
import com.sentinelpay.payment.support.OpenApiContractSupport;
import org.junit.jupiter.api.BeforeEach;
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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.sentinelpay.payment.support.GatewayTestAuth.asMerchant;
import static com.sentinelpay.payment.support.GatewayTestAuth.asOps;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
@AutoConfigureMockMvc
@Import({GlobalExceptionHandler.class, SecurityConfig.class, GatewaySecurityAutoConfiguration.class, PaymentAuthzIT.StubRiskConfig.class})
class PaymentAuthzIT {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    PaymentRepository paymentRepository;

    @Autowired
    PaymentStatusHistoryRepository paymentStatusHistoryRepository;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PaymentTestContainers.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", PaymentTestContainers.POSTGRES::getUsername);
        registry.add("spring.datasource.password", PaymentTestContainers.POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", PaymentTestContainers.KAFKA::getBootstrapServers);
        registry.add("sentinelpay.security.gateway-secret", () -> GatewayTestAuth.SECRET);
    }

    @BeforeEach
    void clean() {
        paymentStatusHistoryRepository.deleteAll();
        paymentRepository.deleteAll();
    }

    @Test
    void getPayment_withoutGatewaySecret_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/payments/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getPayment_otherMerchantsPayment_returns404() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID caller = UUID.randomUUID();
        PaymentEntity payment = seedPayment(owner);

        mockMvc.perform(asMerchant(get("/api/v1/payments/{id}", payment.getId()), caller))
                .andExpect(status().isNotFound());
    }

    @Test
    void getPayment_ownPayment_returns200() throws Exception {
        UUID merchantId = UUID.randomUUID();
        PaymentEntity payment = seedPayment(merchantId);

        mockMvc.perform(asMerchant(get("/api/v1/payments/{id}", payment.getId()), merchantId))
                .andExpect(status().isOk())
                .andExpect(OpenApiContractSupport.openApi());
    }

    @Test
    void trail_withMerchantRole_returns403() throws Exception {
        UUID merchantId = UUID.randomUUID();
        PaymentEntity payment = seedPayment(merchantId);

        mockMvc.perform(asMerchant(get("/api/v1/payments/{id}/trail", payment.getId()), merchantId))
                .andExpect(status().isForbidden());
    }

    @Test
    void trail_withOpsRole_returns200() throws Exception {
        UUID merchantId = UUID.randomUUID();
        PaymentEntity payment = seedPayment(merchantId);

        mockMvc.perform(asOps(get("/api/v1/payments/{id}/trail", payment.getId())))
                .andExpect(status().isOk())
                .andExpect(OpenApiContractSupport.openApi());
    }

    private PaymentEntity seedPayment(UUID merchantId) {
        PaymentEntity payment = new PaymentEntity();
        payment.setMerchantId(merchantId);
        payment.setStatus(PaymentStatus.COMPLETED);
        payment.setAmountCents(2_500);
        payment.setCurrency("USD");
        payment.setProvider("mockpay");
        return paymentRepository.saveAndFlush(payment);
    }

    @TestConfiguration
    static class StubRiskConfig {

        @Bean
        @Primary
        RiskEvaluator stubRiskEvaluator() {
            return input -> new RiskEvaluator.RiskDecision(
                    0.0, "APPROVE", List.of(), "test-stub", false);
        }

        @Bean
        @Primary
        RiskAssessmentClient stubRiskAssessmentClient() {
            RiskAssessmentClient client = mock(RiskAssessmentClient.class);
            when(client.fetch(any())).thenReturn(Optional.empty());
            return client;
        }
    }
}
