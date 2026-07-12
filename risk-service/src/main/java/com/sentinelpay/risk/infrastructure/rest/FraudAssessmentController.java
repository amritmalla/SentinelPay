package com.sentinelpay.risk.infrastructure.rest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelpay.common.error.ApiException;
import com.sentinelpay.common.error.ErrorCode;
import com.sentinelpay.risk.infrastructure.persistence.RiskAssessmentEntity;
import com.sentinelpay.risk.infrastructure.persistence.RiskAssessmentRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class FraudAssessmentController {

    private final RiskAssessmentRepository riskAssessmentRepository;
    private final ObjectMapper objectMapper;

    public FraudAssessmentController(
            RiskAssessmentRepository riskAssessmentRepository,
            ObjectMapper objectMapper) {
        this.riskAssessmentRepository = riskAssessmentRepository;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/api/v1/fraud-assessments/{transactionId}")
    public FraudAssessmentView getFraudAssessment(@PathVariable UUID transactionId) {
        RiskAssessmentEntity entity = riskAssessmentRepository.findByTransactionId(transactionId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "assessment_not_found"));

        return new FraudAssessmentView(
                entity.getTransactionId(),
                entity.getMerchantId(),
                entity.getScore(),
                entity.getRecommendation(),
                deserializeFactors(entity.getContributingFactors()),
                entity.getModelVersion(),
                entity.isFallbackUsed(),
                entity.getCreatedAt());
    }

    private List<String> deserializeFactors(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to deserialize contributing factors", ex);
        }
    }
}
