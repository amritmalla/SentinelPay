package com.sentinelpay.risk.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelpay.risk.domain.scoring.RiskResult;
import com.sentinelpay.risk.domain.scoring.ScoringInput;
import com.sentinelpay.risk.infrastructure.outbox.RiskOutboxEntity;
import com.sentinelpay.risk.infrastructure.outbox.RiskOutboxRepository;
import com.sentinelpay.risk.infrastructure.persistence.RiskAssessmentEntity;
import com.sentinelpay.risk.infrastructure.persistence.RiskAssessmentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
class RiskAssessmentWriter {

    private final RiskAssessmentRepository riskAssessmentRepository;
    private final RiskOutboxRepository riskOutboxRepository;
    private final ObjectMapper objectMapper;
    private final double highAlertThreshold;

    RiskAssessmentWriter(
            RiskAssessmentRepository riskAssessmentRepository,
            RiskOutboxRepository riskOutboxRepository,
            ObjectMapper objectMapper,
            @Value("${sentinelpay.risk.scoring.high-alert-threshold:0.90}") double highAlertThreshold) {
        this.riskAssessmentRepository = riskAssessmentRepository;
        this.riskOutboxRepository = riskOutboxRepository;
        this.objectMapper = objectMapper;
        this.highAlertThreshold = highAlertThreshold;
    }

    @Transactional
    public RiskAssessmentEntity persist(ScoringInput input, RiskResult result, int latencyMs, long velocityCount) {
        RiskAssessmentEntity entity = new RiskAssessmentEntity();
        entity.setTransactionId(input.transactionId());
        entity.setMerchantId(input.merchantId());
        entity.setScore(BigDecimal.valueOf(result.score()).setScale(3, java.math.RoundingMode.HALF_UP));
        entity.setRecommendation(result.recommendation());
        entity.setContributingFactors(serializeJson(result.factors()));
        entity.setFeatures(serializeJson(features(input, velocityCount)));
        entity.setModelVersion(result.modelVersion());
        entity.setFallbackUsed(false);
        entity.setLatencyMs(latencyMs);
        entity = riskAssessmentRepository.saveAndFlush(entity);

        writeOutbox(entity, input, result);
        return entity;
    }

    private void writeOutbox(RiskAssessmentEntity entity, ScoringInput input, RiskResult result) {
        RiskOutboxEntity assessed = new RiskOutboxEntity();
        assessed.setAggregate("risk_assessment");
        assessed.setAggregateId(entity.getId());
        assessed.setEventType("risk.assessed");
        assessed.setPayload(serializeJson(RiskEventPayloads.assessed(
                input.transactionId(),
                input.merchantId(),
                result.score(),
                result.recommendation(),
                result.factors(),
                result.modelVersion())));
        riskOutboxRepository.save(assessed);

        if (result.score() >= highAlertThreshold) {
            RiskOutboxEntity alert = new RiskOutboxEntity();
            alert.setAggregate("risk_assessment");
            alert.setAggregateId(entity.getId());
            alert.setEventType("fraud.alert.high");
            alert.setPayload(serializeJson(RiskEventPayloads.highAlert(
                    input.merchantId(), input.transactionId(), result.score(), result.factors())));
            riskOutboxRepository.save(alert);
        }
    }

    private static Map<String, Object> features(ScoringInput input, long velocityCount) {
        Map<String, Object> features = new LinkedHashMap<>();
        features.put("amountCents", input.amountCents());
        features.put("currency", input.currency());
        features.put("velocityLastHour", velocityCount);
        if (input.customerEmail() != null) {
            features.put("customerEmail", input.customerEmail());
        }
        return features;
    }

    private String serializeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize JSON", ex);
        }
    }
}
