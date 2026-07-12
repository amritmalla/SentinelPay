package com.sentinelpay.risk.infrastructure.persistence;

import com.sentinelpay.risk.RiskTestContainers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.task.scheduling.enabled=false",
        "sentinelpay.risk.grpc.port=0"
})
class RiskAssessmentPersistenceIT {

    @Autowired
    RiskAssessmentRepository repository;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", RiskTestContainers.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", RiskTestContainers.POSTGRES::getUsername);
        registry.add("spring.datasource.password", RiskTestContainers.POSTGRES::getPassword);
        registry.add("spring.data.redis.host", RiskTestContainers.REDIS::getHost);
        registry.add("spring.data.redis.port", () -> RiskTestContainers.REDIS.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers", RiskTestContainers.KAFKA::getBootstrapServers);
    }

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void riskAssessment_roundTripsAndFindsByTransactionId() {
        UUID transactionId = UUID.randomUUID();
        RiskAssessmentEntity entity = new RiskAssessmentEntity();
        entity.setTransactionId(transactionId);
        entity.setMerchantId(UUID.randomUUID());
        entity.setScore(new BigDecimal("0.150"));
        entity.setRecommendation("APPROVE");
        entity.setContributingFactors("[\"no_risk_signals\"]");
        entity.setFeatures("{\"velocityLastHour\":0}");
        entity.setModelVersion("rules-v1.0.0");
        entity.setFallbackUsed(false);
        entity.setLatencyMs(5);

        RiskAssessmentEntity saved = repository.saveAndFlush(entity);
        RiskAssessmentEntity loaded = repository.findByTransactionId(transactionId).orElseThrow();

        assertThat(loaded.getId()).isEqualTo(saved.getId());
        assertThat(loaded.getRecommendation()).isEqualTo("APPROVE");
        assertThat(loaded.getScore()).isEqualByComparingTo("0.150");
    }

    @Test
    void riskAssessment_duplicateTransactionIdThrows() {
        UUID transactionId = UUID.randomUUID();
        RiskAssessmentEntity first = assessment(transactionId);
        repository.saveAndFlush(first);

        RiskAssessmentEntity second = assessment(transactionId);
        assertThatThrownBy(() -> repository.saveAndFlush(second))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static RiskAssessmentEntity assessment(UUID transactionId) {
        RiskAssessmentEntity entity = new RiskAssessmentEntity();
        entity.setTransactionId(transactionId);
        entity.setMerchantId(UUID.randomUUID());
        entity.setScore(new BigDecimal("0.500"));
        entity.setRecommendation("REVIEW");
        entity.setContributingFactors("[\"amount:60000\"]");
        entity.setModelVersion("rules-v1.0.0");
        entity.setFallbackUsed(false);
        return entity;
    }
}
