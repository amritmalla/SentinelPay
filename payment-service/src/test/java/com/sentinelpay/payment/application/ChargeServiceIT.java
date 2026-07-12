package com.sentinelpay.payment.application;

import com.sentinelpay.common.error.ApiException;
import com.sentinelpay.common.error.ErrorCode;
import com.sentinelpay.payment.PaymentTestContainers;
import com.sentinelpay.payment.application.model.ChargeCommand;
import com.sentinelpay.payment.application.model.ChargeResult;
import com.sentinelpay.payment.application.port.RiskEvaluator;
import com.sentinelpay.payment.domain.PaymentStatus;
import com.sentinelpay.payment.domain.Provider;
import com.sentinelpay.payment.infrastructure.outbox.PaymentOutboxEntity;
import com.sentinelpay.payment.infrastructure.outbox.PaymentOutboxRepository;
import com.sentinelpay.payment.infrastructure.persistence.IdempotencyKeyEntity;
import com.sentinelpay.payment.infrastructure.persistence.IdempotencyKeyId;
import com.sentinelpay.payment.infrastructure.persistence.IdempotencyKeyRepository;
import com.sentinelpay.payment.infrastructure.persistence.PaymentAttemptEntity;
import com.sentinelpay.payment.infrastructure.persistence.PaymentAttemptRepository;
import com.sentinelpay.payment.infrastructure.persistence.PaymentEntity;
import com.sentinelpay.payment.infrastructure.persistence.PaymentRepository;
import com.sentinelpay.payment.infrastructure.persistence.RefundRepository;
import com.sentinelpay.payment.infrastructure.persistence.PaymentStatusHistoryEntity;
import com.sentinelpay.payment.infrastructure.persistence.PaymentStatusHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class ChargeServiceIT {

    @Autowired
    ChargeService chargeService;

    @Autowired
    PaymentRepository paymentRepository;

    @Autowired
    PaymentAttemptRepository paymentAttemptRepository;

    @Autowired
    PaymentOutboxRepository paymentOutboxRepository;

    @Autowired
    IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    PaymentStatusHistoryRepository paymentStatusHistoryRepository;

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
    void cleanDatabase() {
        paymentOutboxRepository.deleteAll();
        refundRepository.deleteAll();
        paymentAttemptRepository.deleteAll();
        paymentStatusHistoryRepository.deleteAll();
        idempotencyKeyRepository.deleteAll();
        paymentRepository.deleteAll();
    }

    @Test
    void charge_happyPath_persistsAggregateAndOutbox() {
        UUID merchantId = UUID.randomUUID();
        ChargeCommand command = new ChargeCommand(
                merchantId, 2500, "USD", "buyer@example.com", "idem-1", "corr-1");

        ChargeResult result = chargeService.charge(command);

        assertThat(result.status()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(result.provider()).isEqualTo(Provider.MOCKPAY);
        assertThat(result.paymentId()).isNotNull();
        assertThat(result.trailId()).isEqualTo(result.paymentId());

        List<PaymentEntity> payments = paymentRepository.findAll();
        assertThat(payments).hasSize(1);
        assertThat(payments.get(0).getStatus()).isEqualTo(PaymentStatus.COMPLETED);

        List<PaymentAttemptEntity> attempts = paymentAttemptRepository.findAll();
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).getOutcome()).isEqualTo("CAPTURED");

        List<PaymentOutboxEntity> outboxRows = paymentOutboxRepository.findAll();
        assertThat(outboxRows).hasSize(1);
        assertThat(outboxRows.get(0).getEventType()).isEqualTo("payment.completed");
        assertThat(outboxRows.get(0).getPublishedAt()).isNull();
        assertThat(outboxRows.get(0).getPayload()).contains("customerEmail");

        List<IdempotencyKeyEntity> keys = idempotencyKeyRepository.findAll();
        assertThat(keys).hasSize(1);
        assertThat(keys.get(0).getResponseStatus()).isEqualTo((short) 200);
        assertThat(keys.get(0).getPaymentId()).isEqualTo(result.paymentId());

        assertHistoryTrail(
                result.paymentId(),
                "null→CREATED",
                "CREATED→RISK_EVALUATED",
                "RISK_EVALUATED→AUTHORIZING",
                "AUTHORIZING→AUTHORIZED",
                "AUTHORIZED→CAPTURED",
                "CAPTURED→COMPLETED");
    }

    @Test
    void charge_idempotentRetry_returnsStoredResultWithoutDuplicatePayment() {
        UUID merchantId = UUID.randomUUID();
        ChargeCommand command = new ChargeCommand(
                merchantId, 2500, "USD", "buyer@example.com", "idem-2", "corr-2");

        ChargeResult first = chargeService.charge(command);
        ChargeResult second = chargeService.charge(command);

        assertThat(second).isEqualTo(first);
        assertThat(paymentRepository.count()).isEqualTo(1);
        assertThat(paymentAttemptRepository.count()).isEqualTo(1);
        assertThat(paymentOutboxRepository.count()).isEqualTo(1);
        assertThat(idempotencyKeyRepository.count()).isEqualTo(1);
    }

    @Test
    void charge_sameKeyDifferentBody_throwsIdempotencyConflict() {
        UUID merchantId = UUID.randomUUID();
        ChargeCommand first = new ChargeCommand(
                merchantId, 2500, "USD", "buyer@example.com", "idem-conflict", "corr-a");
        ChargeCommand second = new ChargeCommand(
                merchantId, 5000, "USD", "buyer@example.com", "idem-conflict", "corr-b");

        chargeService.charge(first);

        assertThatThrownBy(() -> chargeService.charge(second))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).errorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT));
    }

    @Test
    void charge_inFlightDuplicate_throwsConflict() {
        UUID merchantId = UUID.randomUUID();
        ChargeCommand command = new ChargeCommand(
                merchantId, 2500, "USD", "buyer@example.com", "idem-in-flight", "corr-c");

        IdempotencyKeyEntity inFlight = new IdempotencyKeyEntity();
        inFlight.setId(new IdempotencyKeyId(merchantId, "idem-in-flight"));
        inFlight.setRequestHash(requestHash(command));
        inFlight.setResponseStatus((short) 0);
        inFlight.setResponseBody("{}");
        inFlight.setExpiresAt(Instant.now().plus(72, ChronoUnit.HOURS));
        idempotencyKeyRepository.saveAndFlush(inFlight);

        assertThatThrownBy(() -> chargeService.charge(command))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.errorCode()).isEqualTo(ErrorCode.CONFLICT);
                    assertThat(api.getMessage()).isEqualTo("charge_in_progress");
                });
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

    private static String requestHash(ChargeCommand command) {
        String raw = command.merchantId()
                + "|" + command.amountCents()
                + "|" + command.currency()
                + "|" + command.customerEmail();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
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
