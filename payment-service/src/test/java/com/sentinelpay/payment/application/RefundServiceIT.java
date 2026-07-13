package com.sentinelpay.payment.application;

import com.sentinelpay.common.error.ApiException;
import com.sentinelpay.common.error.ErrorCode;
import com.sentinelpay.payment.PaymentTestContainers;
import com.sentinelpay.payment.application.model.ChargeCommand;
import com.sentinelpay.payment.application.model.RefundCommand;
import com.sentinelpay.payment.application.model.RefundResult;
import com.sentinelpay.payment.application.port.RiskEvaluator;
import com.sentinelpay.payment.domain.PaymentStatus;
import com.sentinelpay.payment.infrastructure.outbox.PaymentOutboxRepository;
import com.sentinelpay.payment.infrastructure.persistence.IdempotencyKeyRepository;
import com.sentinelpay.payment.infrastructure.persistence.PaymentAttemptRepository;
import com.sentinelpay.payment.infrastructure.persistence.PaymentRepository;
import com.sentinelpay.payment.infrastructure.persistence.PaymentStatusHistoryRepository;
import com.sentinelpay.payment.infrastructure.persistence.RefundRepository;
import com.sentinelpay.payment.infrastructure.provider.MockPayProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class RefundServiceIT {

    @Autowired
    ChargeService chargeService;

    @Autowired
    RefundService refundService;

    @Autowired
    PaymentRepository paymentRepository;

    @Autowired
    RefundRepository refundRepository;

    @Autowired
    PaymentOutboxRepository paymentOutboxRepository;

    @Autowired
    IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    PaymentAttemptRepository paymentAttemptRepository;

    @Autowired
    PaymentStatusHistoryRepository paymentStatusHistoryRepository;

    @Autowired
    MockPayProvider mockPayProvider;

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
        mockPayProvider.resetCounters();
    }

    @Test
    void refund_fullRefund_refundsPaymentAndEmitsEvent() {
        UUID paymentId = chargeCompletedPayment(5_000);

        RefundResult result = refundService.refund(refundCommand(paymentId, 5_000, "refund-full"));

        assertThat(result.status()).isEqualTo("REFUNDED");
        assertThat(paymentRepository.findById(paymentId).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.REFUNDED);
        assertThat(refundRepository.count()).isOne();
        assertThat(paymentOutboxRepository.findAll()).extracting(row -> row.getEventType())
                .contains("payment.completed", "payment.refunded");
        assertThat(mockPayProvider.refundCount()).isOne();
    }

    @Test
    void refund_partialThenRemainder_leavesCompletedUntilFullyRefunded() {
        UUID paymentId = chargeCompletedPayment(5_000);

        RefundResult first = refundService.refund(refundCommand(paymentId, 2_000, "partial-1"));
        assertThat(first.status()).isEqualTo("REFUNDED");
        assertThat(paymentRepository.findById(paymentId).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.COMPLETED);

        RefundResult second = refundService.refund(refundCommand(paymentId, 3_000, "partial-2"));
        assertThat(second.status()).isEqualTo("REFUNDED");
        assertThat(paymentRepository.findById(paymentId).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.REFUNDED);
        assertThat(refundRepository.count()).isEqualTo(2);
    }

    @Test
    void refund_overRefund_returns422() {
        UUID paymentId = chargeCompletedPayment(5_000);

        assertThatThrownBy(() -> refundService.refund(refundCommand(paymentId, 6_000, "over")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).errorCode())
                        .isEqualTo(ErrorCode.UNPROCESSABLE_ENTITY));
    }

    @Test
    void refund_nonCompletedPayment_returns409() {
        UUID merchantId = UUID.randomUUID();
        ChargeCommand command = new ChargeCommand(
                merchantId, 2_500, "USD", "buyer@example.com", "charge-block", "corr-1");

        UUID paymentId = chargeService.charge(command).paymentId();
        paymentRepository.findById(paymentId).ifPresent(payment -> {
            payment.setStatus(PaymentStatus.BLOCKED);
            paymentRepository.save(payment);
        });

        assertThatThrownBy(() -> refundService.refund(refundCommand(paymentId, 2_500, "blocked")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.errorCode()).isEqualTo(ErrorCode.CONFLICT);
                    assertThat(api.getMessage()).isEqualTo("payment_not_refundable");
                });
    }

    @Test
    void refund_idempotentRetry_persistsOnceAndRefundsProviderOnce() {
        UUID paymentId = chargeCompletedPayment(4_000);
        RefundCommand command = refundCommand(paymentId, 4_000, "idem-refund");

        RefundResult first = refundService.refund(command);
        RefundResult second = refundService.refund(command);

        assertThat(second).isEqualTo(first);
        assertThat(refundRepository.count()).isOne();
        assertThat(mockPayProvider.refundCount()).isOne();
    }

    private UUID chargeCompletedPayment(long amountCents) {
        UUID merchantId = UUID.randomUUID();
        return chargeService.charge(new ChargeCommand(
                merchantId,
                amountCents,
                "USD",
                "buyer@example.com",
                "charge-" + UUID.randomUUID(),
                "corr-" + UUID.randomUUID())).paymentId();
    }

    private RefundCommand refundCommand(UUID paymentId, long amountCents, String idempotencyKey) {
        UUID merchantId = paymentRepository.findById(paymentId).orElseThrow().getMerchantId();
        return new RefundCommand(paymentId, merchantId, amountCents, "customer_request", idempotencyKey, "corr-refund");
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
