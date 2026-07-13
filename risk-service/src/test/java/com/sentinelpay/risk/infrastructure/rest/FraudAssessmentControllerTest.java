package com.sentinelpay.risk.infrastructure.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelpay.common.error.GlobalExceptionHandler;
import com.sentinelpay.common.security.GatewaySecurityAutoConfiguration;
import com.sentinelpay.risk.RiskTestContainers;
import com.sentinelpay.risk.config.SecurityConfig;
import com.sentinelpay.risk.infrastructure.persistence.RiskAssessmentEntity;
import com.sentinelpay.risk.infrastructure.persistence.RiskAssessmentRepository;
import com.sentinelpay.risk.support.GatewayTestAuth;
import com.sentinelpay.risk.support.OpenApiContractSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static com.sentinelpay.risk.support.GatewayTestAuth.asMerchant;
import static com.sentinelpay.risk.support.GatewayTestAuth.asOps;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.task.scheduling.enabled=false",
        "sentinelpay.risk.grpc.port=0"
})
@AutoConfigureMockMvc
@Import({GlobalExceptionHandler.class, SecurityConfig.class, GatewaySecurityAutoConfiguration.class})
class FraudAssessmentControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    RiskAssessmentRepository riskAssessmentRepository;

    @Autowired
    ObjectMapper objectMapper;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", RiskTestContainers.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", RiskTestContainers.POSTGRES::getUsername);
        registry.add("spring.datasource.password", RiskTestContainers.POSTGRES::getPassword);
        registry.add("spring.data.redis.host", RiskTestContainers.REDIS::getHost);
        registry.add("spring.data.redis.port", () -> RiskTestContainers.REDIS.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers", RiskTestContainers.KAFKA::getBootstrapServers);
        registry.add("sentinelpay.security.gateway-secret", () -> GatewayTestAuth.SECRET);
    }

    @BeforeEach
    void clean() {
        riskAssessmentRepository.deleteAll();
    }

    @Test
    void getFraudAssessment_existing_returnsAssessment() throws Exception {
        UUID transactionId = UUID.randomUUID();
        RiskAssessmentEntity entity = new RiskAssessmentEntity();
        entity.setTransactionId(transactionId);
        entity.setMerchantId(UUID.randomUUID());
        entity.setScore(BigDecimal.valueOf(0.15));
        entity.setRecommendation("APPROVE");
        entity.setContributingFactors(objectMapper.writeValueAsString(java.util.List.of("no_risk_signals")));
        entity.setModelVersion("rules-v1.0.0");
        entity.setFallbackUsed(false);
        riskAssessmentRepository.saveAndFlush(entity);

        mockMvc.perform(asOps(get("/api/v1/fraud-assessments/{transactionId}", transactionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transaction_id").value(transactionId.toString()))
                .andExpect(jsonPath("$.score").value(0.15))
                .andExpect(jsonPath("$.recommendation").value("APPROVE"))
                .andExpect(jsonPath("$.contributing_factors[0]").value("no_risk_signals"))
                .andExpect(jsonPath("$.model_version").value("rules-v1.0.0"))
                .andExpect(jsonPath("$.fallback_used").value(false))
                .andExpect(OpenApiContractSupport.openApi());
    }

    @Test
    void getFraudAssessment_missing_returns404() throws Exception {
        mockMvc.perform(asOps(get("/api/v1/fraud-assessments/{transactionId}", UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void getFraudAssessment_withoutGatewaySecret_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/fraud-assessments/{transactionId}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getFraudAssessment_withMerchantRole_returns403() throws Exception {
        mockMvc.perform(asMerchant(get("/api/v1/fraud-assessments/{transactionId}", UUID.randomUUID()), UUID.randomUUID()))
                .andExpect(status().isForbidden());
    }
}
