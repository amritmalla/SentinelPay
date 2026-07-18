package com.sentinelpay.payment.infrastructure.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelpay.common.error.GlobalExceptionHandler;
import com.sentinelpay.common.security.GatewaySecurityAutoConfiguration;
import com.sentinelpay.payment.PaymentTestContainers;
import com.sentinelpay.payment.application.port.RiskEvaluator;
import com.sentinelpay.payment.config.SecurityConfig;
import com.sentinelpay.payment.domain.PaymentStatus;
import com.sentinelpay.payment.infrastructure.persistence.PaymentAttemptEntity;
import com.sentinelpay.payment.infrastructure.persistence.PaymentAttemptRepository;
import com.sentinelpay.payment.infrastructure.persistence.PaymentEntity;
import com.sentinelpay.payment.infrastructure.persistence.PaymentRepository;
import com.sentinelpay.payment.infrastructure.persistence.PaymentStatusHistoryRepository;
import com.sentinelpay.payment.infrastructure.risk.RiskAssessmentClient;
import com.sentinelpay.payment.support.GatewayTestAuth;
import com.sentinelpay.payment.support.OpenApiContractSupport;
import com.sentinelpay.payment.support.PaymentDatabaseReset;
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
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.sentinelpay.payment.support.GatewayTestAuth.asMerchant;
import static com.sentinelpay.payment.support.GatewayTestAuth.asOps;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
@AutoConfigureMockMvc
@Import({GlobalExceptionHandler.class, SecurityConfig.class, GatewaySecurityAutoConfiguration.class, PaymentSummaryIT.StubRiskConfig.class})
class PaymentSummaryIT {

    private static final Set<String> FORBIDDEN_FIELD_NAMES = Set.of(
            "policy",
            "bandit",
            "breaker_state",
            "score",
            "contributing_factors",
            "model_version",
            "routing",
            "ordered_providers",
            "flags",
            "split",
            "rationale",
            "attempts",
            "fallback_used");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    PaymentRepository paymentRepository;

    @Autowired
    PaymentAttemptRepository paymentAttemptRepository;

    @Autowired
    PaymentStatusHistoryRepository paymentStatusHistoryRepository;

    @Autowired
    DataSource dataSource;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PaymentTestContainers.register(registry);
        registry.add("sentinelpay.security.gateway-secret", () -> GatewayTestAuth.SECRET);
    }

    @BeforeEach
    void clean() {
        PaymentDatabaseReset.truncateAll(dataSource);
    }

    @Test
    void summary_ownPayment_returnsRedactedPayload() throws Exception {
        UUID merchantId = UUID.randomUUID();
        PaymentEntity payment = seedPayment(merchantId);
        seedAttempt(payment.getId(), (short) 1, "mockpay", "HARD_FAIL");
        seedAttempt(payment.getId(), (short) 2, "stripe", "CAPTURED");

        mockMvc.perform(asMerchant(get("/api/v1/payments/{id}/summary", payment.getId()), merchantId))
                .andExpect(status().isOk())
                .andExpect(OpenApiContractSupport.openApi())
                .andExpect(jsonPath("$.payment_id").value(payment.getId().toString()))
                .andExpect(jsonPath("$.risk.score_band").value("LOW"))
                .andExpect(jsonPath("$.risk.recommendation").value("APPROVE"))
                .andExpect(jsonPath("$.outcome.provider_count").value(2))
                .andExpect(jsonPath("$.outcome.recovered").value(true))
                .andExpect(jsonPath("$.outcome.final_provider_slot").value("mockpay"));
    }

    @Test
    void summary_doesNotExposeOpsOnlyFields() throws Exception {
        UUID merchantId = UUID.randomUUID();
        PaymentEntity payment = seedPayment(merchantId);

        MvcResult result = mockMvc.perform(asMerchant(get("/api/v1/payments/{id}/summary", payment.getId()), merchantId))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        assertNoForbiddenFields(root);
    }

    @Test
    void summary_otherMerchantsPayment_returns404() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID caller = UUID.randomUUID();
        PaymentEntity payment = seedPayment(owner);

        mockMvc.perform(asMerchant(get("/api/v1/payments/{id}/summary", payment.getId()), caller))
                .andExpect(status().isNotFound());
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

    private static void assertNoForbiddenFields(JsonNode node) {
        if (node.isObject()) {
            Iterator<String> names = node.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                assertThat(FORBIDDEN_FIELD_NAMES)
                        .as("field %s must not appear in merchant summary", name)
                        .doesNotContain(name);
                assertNoForbiddenFields(node.get(name));
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                assertNoForbiddenFields(child);
            }
        }
    }

    private PaymentEntity seedPayment(UUID merchantId) {
        PaymentEntity payment = new PaymentEntity();
        payment.setMerchantId(merchantId);
        payment.setStatus(PaymentStatus.COMPLETED);
        payment.setAmountCents(2_500);
        payment.setCurrency("USD");
        payment.setProvider("mockpay");
        payment.setRiskScore(new BigDecimal("0.12"));
        payment.setRiskRecommendation("APPROVE");
        payment.setRiskModelVersion("test-model");
        payment.setRiskFallbackUsed(false);
        return paymentRepository.saveAndFlush(payment);
    }

    private void seedAttempt(UUID paymentId, short attemptNumber, String provider, String outcome) {
        PaymentAttemptEntity attempt = new PaymentAttemptEntity();
        attempt.setPaymentId(paymentId);
        attempt.setAttemptNumber(attemptNumber);
        attempt.setProvider(provider);
        attempt.setOutcome(outcome);
        paymentAttemptRepository.saveAndFlush(attempt);
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
            when(client.fetch(any(), any())).thenReturn(Optional.empty());
            return client;
        }
    }
}
