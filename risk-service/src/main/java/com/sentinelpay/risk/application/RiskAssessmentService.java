package com.sentinelpay.risk.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelpay.risk.application.model.VelocityReadResult;
import com.sentinelpay.risk.application.model.VelocityWindow;
import com.sentinelpay.risk.application.port.VelocityStore;
import com.sentinelpay.risk.domain.scoring.RiskResult;
import com.sentinelpay.risk.domain.scoring.RiskScorer;
import com.sentinelpay.risk.domain.scoring.ScoringInput;
import com.sentinelpay.risk.infrastructure.metrics.RiskMetrics;
import com.sentinelpay.risk.infrastructure.persistence.RiskAssessmentEntity;
import com.sentinelpay.risk.infrastructure.persistence.RiskAssessmentRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RiskAssessmentService {

    private final RiskAssessmentRepository riskAssessmentRepository;
    private final RiskAssessmentWriter riskAssessmentWriter;
    private final RiskScorer riskScorer;
    private final VelocityStore velocityStore;
    private final ObjectMapper objectMapper;
    private final RiskMetrics riskMetrics;

    public RiskAssessmentService(
            RiskAssessmentRepository riskAssessmentRepository,
            RiskAssessmentWriter riskAssessmentWriter,
            RiskScorer riskScorer,
            VelocityStore velocityStore,
            ObjectMapper objectMapper,
            RiskMetrics riskMetrics) {
        this.riskAssessmentRepository = riskAssessmentRepository;
        this.riskAssessmentWriter = riskAssessmentWriter;
        this.riskScorer = riskScorer;
        this.velocityStore = velocityStore;
        this.objectMapper = objectMapper;
        this.riskMetrics = riskMetrics;
    }

    public RiskResult assess(ScoringInput input) {
        return riskAssessmentRepository
                .findByTransactionId(input.transactionId())
                .map(entity -> {
                    if (entity.isFallbackUsed()) {
                        riskMetrics.recordFallback();
                    }
                    riskMetrics.recordDecision(entity.getRecommendation());
                    return toResult(entity);
                })
                .orElseGet(() -> scoreAndPersist(input));
    }

    private RiskResult scoreAndPersist(ScoringInput input) {
        long startNanos = System.nanoTime();
        VelocityReadResult velocity = velocityStore.getCount(input.customerEmail(), VelocityWindow.ONE_HOUR);
        ScoringInput scoredInput = input.withVelocity(velocity.count());
        RiskResult result = riskScorer.score(scoredInput);
        int latencyMs = (int) ((System.nanoTime() - startNanos) / 1_000_000);

        try {
            riskAssessmentWriter.persist(scoredInput, result, latencyMs, velocity.count());
        } catch (DataIntegrityViolationException ex) {
            RiskAssessmentEntity raced = riskAssessmentRepository
                    .findByTransactionId(input.transactionId())
                    .orElseThrow(() -> ex);
            return toResult(raced);
        }

        velocityStore.increment(input.customerEmail(), VelocityWindow.ONE_HOUR);
        riskMetrics.recordDecision(result.recommendation());
        return result;
    }

    private RiskResult toResult(RiskAssessmentEntity entity) {
        return new RiskResult(
                entity.getScore().doubleValue(),
                entity.getRecommendation(),
                deserializeFactors(entity.getContributingFactors()),
                entity.getModelVersion());
    }

    private List<String> deserializeFactors(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to deserialize contributing factors", ex);
        }
    }
}
