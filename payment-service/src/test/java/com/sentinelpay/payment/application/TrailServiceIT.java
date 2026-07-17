package com.sentinelpay.payment.application;

import com.sentinelpay.payment.PaymentTestContainers;
import com.sentinelpay.payment.domain.PaymentStatus;
import com.sentinelpay.payment.infrastructure.persistence.PaymentAttemptEntity;
import com.sentinelpay.payment.infrastructure.persistence.IdempotencyKeyRepository;
import com.sentinelpay.payment.infrastructure.persistence.PaymentAttemptRepository;
import com.sentinelpay.payment.infrastructure.persistence.PaymentEntity;
import com.sentinelpay.payment.infrastructure.persistence.PaymentRepository;
import com.sentinelpay.payment.infrastructure.persistence.PaymentStatusHistoryRepository;
import com.sentinelpay.payment.infrastructure.persistence.RefundRepository;
import com.sentinelpay.payment.infrastructure.rest.DecisionTrail;
import com.sentinelpay.payment.infrastructure.risk.RiskAssessmentClient;
import com.sentinelpay.payment.infrastructure.risk.RiskTrailView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
@Import(TrailServiceIT.StubRiskClientConfig.class)
class TrailServiceIT {

    @Autowired
    TrailService trailService;

    @Autowired
    PaymentRepository paymentRepository;

    @Autowired
    PaymentAttemptRepository paymentAttemptRepository;

    @Autowired
    PaymentStatusHistoryRepository paymentStatusHistoryRepository;

    @Autowired
    IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    RefundRepository refundRepository;

    @Autowired
    RiskAssessmentClient riskAssessmentClient;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PaymentTestContainers.register(registry);
    }

    @BeforeEach
    void clean() {
        refundRepository.deleteAll();
        paymentAttemptRepository.deleteAll();
        paymentStatusHistoryRepository.deleteAll();
        idempotencyKeyRepository.deleteAll();
        paymentRepository.deleteAll();
    }

    @Test
    void trail_withRiskAndAttempts_composesDecisionTrail() {
        PaymentEntity payment = new PaymentEntity();
        payment.setMerchantId(UUID.randomUUID());
        payment.setStatus(PaymentStatus.COMPLETED);
        payment.setAmountCents(2_500);
        payment.setCurrency("USD");
        payment.setProvider("mockpay");
        payment = paymentRepository.saveAndFlush(payment);
        UUID paymentId = payment.getId();

        PaymentAttemptEntity attempt = new PaymentAttemptEntity();
        attempt.setPaymentId(paymentId);
        attempt.setAttemptNumber((short) 1);
        attempt.setProvider("mockpay");
        attempt.setOutcome("CAPTURED");
        attempt.setProviderRef("mockpay_123");
        paymentAttemptRepository.saveAndFlush(attempt);

        RiskTrailView risk = new RiskTrailView(
                paymentId,
                BigDecimal.valueOf(0.1),
                "APPROVE",
                List.of("no_risk_signals"),
                "rules-v1.0.0",
                false);
        when(riskAssessmentClient.fetch(paymentId, payment.getMerchantId())).thenReturn(Optional.of(risk));

        DecisionTrail trail = trailService.trail(paymentId);

        assertThat(trail.paymentId()).isEqualTo(paymentId);
        assertThat(trail.status()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(trail.attempts()).hasSize(1);
        assertThat(trail.attempts().get(0).providerRef()).isEqualTo("mockpay_123");
        assertThat(trail.risk()).isNotNull();
        assertThat(trail.risk().recommendation()).isEqualTo("APPROVE");
    }

    @Test
    void trail_whenRiskUnavailable_returnsNullRisk() {
        PaymentEntity payment = new PaymentEntity();
        payment.setMerchantId(UUID.randomUUID());
        payment.setStatus(PaymentStatus.COMPLETED);
        payment.setAmountCents(2_500);
        payment.setCurrency("USD");
        payment = paymentRepository.saveAndFlush(payment);
        UUID paymentId = payment.getId();

        when(riskAssessmentClient.fetch(any(), any())).thenReturn(Optional.empty());

        DecisionTrail trail = trailService.trail(paymentId);

        assertThat(trail.risk()).isNull();
        assertThat(trail.paymentId()).isEqualTo(paymentId);
    }

    @TestConfiguration
    static class StubRiskClientConfig {

        @Bean
        @Primary
        RiskAssessmentClient riskAssessmentClient() {
            return mock(RiskAssessmentClient.class);
        }
    }
}
