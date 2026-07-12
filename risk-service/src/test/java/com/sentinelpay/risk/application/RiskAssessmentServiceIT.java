package com.sentinelpay.risk.application;

import com.sentinelpay.risk.RiskTestContainers;
import com.sentinelpay.risk.application.model.VelocityWindow;
import com.sentinelpay.risk.application.port.VelocityStore;
import com.sentinelpay.risk.domain.scoring.RiskResult;
import com.sentinelpay.risk.domain.scoring.ScoringInput;
import com.sentinelpay.risk.infrastructure.outbox.RiskOutboxRepository;
import com.sentinelpay.risk.infrastructure.persistence.RiskAssessmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.task.scheduling.enabled=false",
        "sentinelpay.risk.grpc.port=0"
})
class RiskAssessmentServiceIT {

    @Autowired
    RiskAssessmentService riskAssessmentService;

    @Autowired
    RiskAssessmentRepository riskAssessmentRepository;

    @Autowired
    RiskOutboxRepository riskOutboxRepository;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    VelocityStore velocityStore;

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
        riskOutboxRepository.deleteAll();
        riskAssessmentRepository.deleteAll();
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @Test
    void assess_lowRiskInput_persistsApproveAndRiskAssessedOnly() {
        ScoringInput input = input(2_500, "buyer@example.com");

        RiskResult result = riskAssessmentService.assess(input);

        assertThat(result.recommendation()).isEqualTo("APPROVE");
        assertThat(riskAssessmentRepository.findByTransactionId(input.transactionId())).isPresent();
        assertThat(riskOutboxRepository.findAll()).hasSize(1);
        assertThat(riskOutboxRepository.findAll().get(0).getEventType()).isEqualTo("risk.assessed");
    }

    @Test
    void assess_highScore_persistsBlockAndBothOutboxEvents() {
        String email = "alert-" + UUID.randomUUID() + "@mailinator.com";
        for (int i = 0; i < 5; i++) {
            riskAssessmentService.assess(input(1_000, email));
        }

        RiskResult result = riskAssessmentService.assess(input(120_000, email));

        assertThat(result.recommendation()).isEqualTo("BLOCK");
        assertThat(result.score()).isGreaterThanOrEqualTo(0.90);
        assertThat(riskOutboxRepository.findAll()).extracting(row -> row.getEventType())
                .contains("risk.assessed", "fraud.alert.high");
    }

    @Test
    void assess_repeatedEmail_includesVelocitySignalOnSixthAssessment() {
        String email = "velocity-" + UUID.randomUUID() + "@example.com";

        RiskResult sixth = null;
        for (int i = 0; i < 6; i++) {
            sixth = riskAssessmentService.assess(input(2_500, email));
        }

        assertThat(sixth.factors()).anyMatch(f -> f.startsWith("velocity_1h:"));
    }

    @Test
    void assess_idempotentReplay_persistsOnceAndIncrementsVelocityOnce() {
        ScoringInput input = input(2_500, "idem-" + UUID.randomUUID() + "@example.com");

        RiskResult first = riskAssessmentService.assess(input);
        RiskResult second = riskAssessmentService.assess(input);

        assertThat(second).isEqualTo(first);
        assertThat(riskAssessmentRepository.count()).isOne();
        assertThat(velocityStore.getCount(input.customerEmail(), VelocityWindow.ONE_HOUR).count())
                .isEqualTo(1);
    }

    private static ScoringInput input(long amountCents, String email) {
        return new ScoringInput(UUID.randomUUID(), UUID.randomUUID(), amountCents, "USD", email, 0);
    }
}
