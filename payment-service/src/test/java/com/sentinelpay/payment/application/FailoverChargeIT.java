package com.sentinelpay.payment.application;

import com.sentinelpay.payment.PaymentTestContainers;
import com.sentinelpay.payment.application.model.BeginChargeOutcome;
import com.sentinelpay.payment.application.model.ChargeCommand;
import com.sentinelpay.payment.application.model.ChargeResult;
import com.sentinelpay.payment.application.model.ProviderBehavior;
import com.sentinelpay.payment.application.model.ProviderOutcome.Outcome;
import com.sentinelpay.payment.application.port.PaymentProvider;
import com.sentinelpay.payment.application.port.RiskEvaluator;
import com.sentinelpay.payment.domain.PaymentStatus;
import com.sentinelpay.payment.domain.Provider;
import com.sentinelpay.payment.infrastructure.outbox.PaymentOutboxRepository;
import com.sentinelpay.payment.infrastructure.persistence.IdempotencyKeyRepository;
import com.sentinelpay.payment.infrastructure.persistence.PaymentAttemptEntity;
import com.sentinelpay.payment.infrastructure.persistence.PaymentAttemptRepository;
import com.sentinelpay.payment.infrastructure.persistence.PaymentEntity;
import com.sentinelpay.payment.infrastructure.persistence.PaymentRepository;
import com.sentinelpay.payment.infrastructure.persistence.PaymentStatusHistoryEntity;
import com.sentinelpay.payment.infrastructure.persistence.PaymentStatusHistoryRepository;
import com.sentinelpay.payment.infrastructure.persistence.RefundRepository;
import com.sentinelpay.payment.infrastructure.provider.MockPayProvider;
import com.sentinelpay.payment.infrastructure.provider.StripeStubProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class FailoverChargeIT {

    @Autowired
    ChargeService chargeService;

    @Autowired
    PaymentTransactionService paymentTransactionService;

    @Autowired
    ReconciliationSweep reconciliationSweep;

    @Autowired
    ProviderBehavior providerBehavior;

    @Autowired
    MockPayProvider mockPayProvider;

    @Autowired
    StripeStubProvider stripeStubProvider;

    @Autowired
    PaymentStatusHistoryRepository paymentStatusHistoryRepository;

    @Autowired
    PaymentRepository paymentRepository;

    @Autowired
    PaymentAttemptRepository paymentAttemptRepository;

    @Autowired
    PaymentOutboxRepository paymentOutboxRepository;

    @Autowired
    IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    RefundRepository refundRepository;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PaymentTestContainers.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", PaymentTestContainers.POSTGRES::getUsername);
        registry.add("spring.datasource.password", PaymentTestContainers.POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", PaymentTestContainers.KAFKA::getBootstrapServers);
    }

    @BeforeEach
    void reset() {
        paymentOutboxRepository.deleteAll();
        refundRepository.deleteAll();
        paymentAttemptRepository.deleteAll();
        paymentStatusHistoryRepository.deleteAll();
        idempotencyKeyRepository.deleteAll();
        paymentRepository.deleteAll();
        providerBehavior.reset();
        mockPayProvider.resetCounters();
        stripeStubProvider.resetCounters();
    }

    @Test
    void happyPath_completedWithSingleAuthAndCapture() {
        ChargeResult result = chargeService.charge(command("happy-1"));

        assertThat(result.status()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(result.provider()).isEqualTo(Provider.MOCKPAY);
        assertDoubleChargeInvariant(PaymentStatus.COMPLETED, Provider.MOCKPAY);
        assertCompletedHistoryTrail(result.paymentId());
    }

    @Test
    void hardFailFailover_completesOnStripe() {
        providerBehavior.program(Provider.MOCKPAY, Outcome.HARD_FAIL);

        ChargeResult result = chargeService.charge(command("failover-hard"));

        assertThat(result.status()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(result.provider()).isEqualTo(Provider.STRIPE);
        assertThat(mockPayProvider.authorizationCount()).isZero();
        assertThat(stripeStubProvider.authorizationCount()).isEqualTo(1);
        assertDoubleChargeInvariant(PaymentStatus.COMPLETED, Provider.STRIPE);
    }

    @Test
    void retryableFailover_completesWithSingleCapture() {
        providerBehavior.program(Provider.MOCKPAY, Outcome.RETRYABLE);

        ChargeResult result = chargeService.charge(command("failover-retry"));

        assertThat(result.status()).isEqualTo(PaymentStatus.COMPLETED);
        assertDoubleChargeInvariant(PaymentStatus.COMPLETED, result.provider());
    }

    @Test
    void ambiguousAuthorized_reconcilesOnMockPayWithoutStripeFailover() {
        providerBehavior.program(Provider.MOCKPAY, Outcome.AMBIGUOUS_TIMEOUT);

        ChargeResult result = chargeService.charge(command("ambig-auth"));

        assertThat(result.status()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(result.provider()).isEqualTo(Provider.MOCKPAY);
        assertThat(mockPayProvider.authorizationCount()).isEqualTo(1);
        assertThat(stripeStubProvider.authorizationCount()).isZero();
        assertDoubleChargeInvariant(PaymentStatus.COMPLETED, Provider.MOCKPAY);
    }

    @Test
    void ambiguousNotAuthorized_failsoverToStripe() {
        providerBehavior.program(
                Provider.MOCKPAY, ProviderBehavior.ProgrammedOutcome.ambiguousWithoutStore());

        ChargeResult result = chargeService.charge(command("ambig-no-auth"));

        assertThat(result.status()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(result.provider()).isEqualTo(Provider.STRIPE);
        assertThat(mockPayProvider.authorizationCount()).isZero();
        assertThat(stripeStubProvider.authorizationCount()).isEqualTo(1);
        assertDoubleChargeInvariant(PaymentStatus.COMPLETED, Provider.STRIPE);
    }

    @Test
    void bothProvidersFail_paymentFailedWithNoCaptures() {
        providerBehavior.program(Provider.MOCKPAY, Outcome.HARD_FAIL);
        providerBehavior.program(Provider.STRIPE, Outcome.HARD_FAIL);

        ChargeResult result = chargeService.charge(command("both-fail"));

        assertThat(result.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(totalCaptureCount()).isZero();
        assertThat(paymentRepository.count()).isEqualTo(1);
        assertThat(paymentOutboxRepository.findAll().stream()
                .anyMatch(r -> "payment.failed".equals(r.getEventType()))).isTrue();
        assertFailedHistoryTrail(result.paymentId());
    }

    @Test
    void crashMidAuthorize_sweepCompletesPayment() {
        ChargeCommand cmd = command("crash-sweep");
        BeginChargeOutcome begin = paymentTransactionService.beginCharge(cmd);
        UUID paymentId = ((BeginChargeOutcome.Started) begin).paymentId();

        paymentTransactionService.recordDecision(paymentId, cmd, approveRisk());
        String downstreamKey = ChargeService.downstreamKey(paymentId, Provider.MOCKPAY, (short) 1);
        paymentTransactionService.startAttempt(paymentId, Provider.MOCKPAY, (short) 1, downstreamKey);
        mockPayProvider.authorize(new PaymentProvider.AuthorizeRequest(
                paymentId, downstreamKey, cmd.amountCents(), cmd.currency()));

        reconciliationSweep.sweep(Instant.now().plus(1, ChronoUnit.DAYS));

        PaymentEntity payment = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertDoubleChargeInvariant(PaymentStatus.COMPLETED, Provider.MOCKPAY);
    }

    @Test
    void idempotentRetryUnderFailover_onePaymentOneCapture() {
        providerBehavior.program(Provider.MOCKPAY, Outcome.HARD_FAIL);
        ChargeCommand cmd = command("idem-failover");

        ChargeResult first = chargeService.charge(cmd);
        ChargeResult second = chargeService.charge(cmd);

        assertThat(second).isEqualTo(first);
        assertThat(paymentRepository.count()).isEqualTo(1);
        assertDoubleChargeInvariant(PaymentStatus.COMPLETED, Provider.STRIPE);
    }

    @Test
    void chaosRecoveredAuth_atLeast95PercentWithSingleCapture() {
        int total = 50;
        int completed = 0;

        for (int i = 0; i < total; i++) {
            providerBehavior.reset();
            mockPayProvider.resetCounters();
            stripeStubProvider.resetCounters();

            if (ThreadLocalRandom.current().nextDouble() < 0.4) {
                Outcome[] failures = {Outcome.HARD_FAIL, Outcome.RETRYABLE, Outcome.AMBIGUOUS_TIMEOUT};
                Outcome failure = failures[ThreadLocalRandom.current().nextInt(failures.length)];
                providerBehavior.program(Provider.MOCKPAY, failure);
            }

            ChargeResult result = chargeService.charge(command("chaos-" + i));
            if (result.status() == PaymentStatus.COMPLETED) {
                completed++;
                assertThat(totalCaptureCount()).isLessThanOrEqualTo(1);
                long capturedAttempts = paymentAttemptRepository.findByPaymentId(result.paymentId()).stream()
                        .filter(a -> "CAPTURED".equals(a.getOutcome()))
                        .count();
                assertThat(capturedAttempts).isLessThanOrEqualTo(1);
            }
        }

        double rate = (double) completed / total;
        assertThat(rate).isGreaterThanOrEqualTo(0.95);
    }

    private ChargeCommand command(String idempotencyKey) {
        return new ChargeCommand(
                UUID.randomUUID(), 2500, "USD", "buyer@example.com", idempotencyKey, "corr-" + idempotencyKey);
    }

    private static RiskEvaluator.RiskDecision approveRisk() {
        return new RiskEvaluator.RiskDecision(0.0, "APPROVE", List.of(), "test-stub", false);
    }

    private void assertDoubleChargeInvariant(PaymentStatus expectedStatus, Provider expectedProvider) {
        assertThat(paymentRepository.count()).isEqualTo(1);
        PaymentEntity payment = paymentRepository.findAll().get(0);
        assertThat(payment.getStatus()).isEqualTo(expectedStatus);
        if (expectedProvider != null) {
            assertThat(payment.getProvider()).isEqualTo(expectedProvider.dbValue());
        }

        assertThat(mockPayProvider.authorizationCount() + stripeStubProvider.authorizationCount())
                .isLessThanOrEqualTo(1);
        assertThat(totalCaptureCount()).isLessThanOrEqualTo(1);

        long capturedAttempts = paymentAttemptRepository.findAll().stream()
                .filter(a -> "CAPTURED".equals(a.getOutcome()))
                .count();
        assertThat(capturedAttempts).isLessThanOrEqualTo(1);
    }

    private int totalCaptureCount() {
        return mockPayProvider.captureCount() + stripeStubProvider.captureCount();
    }

    private void assertCompletedHistoryTrail(UUID paymentId) {
        assertHistoryTrail(
                paymentId,
                "null→CREATED",
                "CREATED→RISK_EVALUATED",
                "RISK_EVALUATED→AUTHORIZING",
                "AUTHORIZING→AUTHORIZED",
                "AUTHORIZED→CAPTURED",
                "CAPTURED→COMPLETED");
    }

    private void assertFailedHistoryTrail(UUID paymentId) {
        assertHistoryTrail(
                paymentId,
                "null→CREATED",
                "CREATED→RISK_EVALUATED",
                "RISK_EVALUATED→AUTHORIZING",
                "AUTHORIZING→FAILED");
    }

    private void assertHistoryTrail(UUID paymentId, String... expectedTransitions) {
        List<PaymentStatusHistoryEntity> rows =
                paymentStatusHistoryRepository.findByPaymentIdOrderByIdAsc(paymentId);
        assertThat(rows)
                .extracting(row -> {
                    String from = row.getFromStatus() == null ? "null" : row.getFromStatus();
                    return from + "→" + row.getToStatus();
                })
                .containsExactly(expectedTransitions);
    }

    @TestConfiguration
    static class StubRiskConfig {

        @Bean
        @Primary
        RiskEvaluator stubRiskEvaluator() {
            return input -> new RiskEvaluator.RiskDecision(
                    0.0, "APPROVE", List.of(), "test-stub", false);
        }
    }
}
