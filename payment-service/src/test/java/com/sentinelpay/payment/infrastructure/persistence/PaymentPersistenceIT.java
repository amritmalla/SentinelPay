package com.sentinelpay.payment.infrastructure.persistence;

import com.sentinelpay.payment.PaymentTestContainers;
import com.sentinelpay.payment.domain.PaymentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class PaymentPersistenceIT {

    @Autowired
    PaymentRepository paymentRepository;

    @Autowired
    IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    PaymentAttemptRepository paymentAttemptRepository;

    @Autowired
    PaymentStatusHistoryRepository paymentStatusHistoryRepository;

    @Autowired
    RefundRepository refundRepository;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PaymentTestContainers.register(registry);
    }

    @BeforeEach
    void cleanDatabase() {
        refundRepository.deleteAll();
        paymentAttemptRepository.deleteAll();
        paymentStatusHistoryRepository.deleteAll();
        idempotencyKeyRepository.deleteAll();
        paymentRepository.deleteAll();
    }

    @Test
    void paymentEntity_roundTripsWithGeneratedIdAndVersionZero() {
        PaymentEntity payment = new PaymentEntity();
        payment.setMerchantId(UUID.randomUUID());
        payment.setStatus(PaymentStatus.CREATED);
        payment.setAmountCents(2500);
        payment.setCurrency("USD");

        PaymentEntity saved = paymentRepository.saveAndFlush(payment);
        PaymentEntity loaded = paymentRepository.findById(saved.getId()).orElseThrow();

        assertThat(loaded.getId()).isNotNull();
        assertThat(loaded.getVersion()).isZero();
        assertThat(loaded.getStatus()).isEqualTo(PaymentStatus.CREATED);
    }

    @Test
    void idempotencyKey_duplicatePrimaryKeyThrows() {
        UUID merchantId = UUID.randomUUID();
        IdempotencyKeyId keyId = new IdempotencyKeyId(merchantId, "dup-key");

        IdempotencyKeyEntity first = new IdempotencyKeyEntity();
        first.setId(keyId);
        first.setRequestHash("hash-1");
        first.setResponseStatus((short) 200);
        first.setResponseBody("{}");
        first.setExpiresAt(Instant.now().plus(72, ChronoUnit.HOURS));
        idempotencyKeyRepository.saveAndFlush(first);

        IdempotencyKeyEntity second = new IdempotencyKeyEntity();
        second.setId(keyId);
        second.setRequestHash("hash-2");
        second.setResponseStatus((short) 200);
        second.setResponseBody("{}");
        second.setExpiresAt(Instant.now().plus(72, ChronoUnit.HOURS));

        assertThatThrownBy(() -> idempotencyKeyRepository.saveAndFlush(second))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void paymentAttempt_startedWithDownstreamKey_roundTrips() {
        PaymentEntity payment = new PaymentEntity();
        payment.setMerchantId(UUID.randomUUID());
        payment.setStatus(PaymentStatus.CREATED);
        payment.setAmountCents(2500);
        payment.setCurrency("USD");
        payment = paymentRepository.saveAndFlush(payment);

        PaymentAttemptEntity attempt = new PaymentAttemptEntity();
        attempt.setPaymentId(payment.getId());
        attempt.setAttemptNumber((short) 1);
        attempt.setProvider("mockpay");
        attempt.setOutcome("STARTED");
        attempt.setDownstreamKey("pay:mockpay:1");
        attempt = paymentAttemptRepository.saveAndFlush(attempt);

        PaymentAttemptEntity loaded = paymentAttemptRepository.findById(attempt.getId()).orElseThrow();
        assertThat(loaded.getOutcome()).isEqualTo("STARTED");
        assertThat(loaded.getDownstreamKey()).isEqualTo("pay:mockpay:1");
    }

    @Test
    void paymentEntity_updatedAtAdvancesOnModification() throws InterruptedException {
        PaymentEntity payment = new PaymentEntity();
        payment.setMerchantId(UUID.randomUUID());
        payment.setStatus(PaymentStatus.CREATED);
        payment.setAmountCents(2500);
        payment.setCurrency("USD");

        PaymentEntity saved = paymentRepository.saveAndFlush(payment);
        PaymentEntity loaded = paymentRepository.findById(saved.getId()).orElseThrow();
        Instant createdAt = loaded.getCreatedAt();
        Instant firstUpdatedAt = loaded.getUpdatedAt();
        assertThat(createdAt).isNotNull();
        assertThat(firstUpdatedAt).isNotNull();

        Thread.sleep(50);
        loaded.setStatus(PaymentStatus.RISK_EVALUATED);
        paymentRepository.saveAndFlush(loaded);
        PaymentEntity reloaded = paymentRepository.findById(loaded.getId()).orElseThrow();

        assertThat(reloaded.getUpdatedAt()).isAfter(firstUpdatedAt);
        assertThat(reloaded.getUpdatedAt()).isAfterOrEqualTo(reloaded.getCreatedAt());
    }
}
