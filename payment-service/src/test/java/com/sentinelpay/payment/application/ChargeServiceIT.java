package com.sentinelpay.payment.application;

import com.sentinelpay.payment.application.model.ChargeCommand;
import com.sentinelpay.payment.application.model.ChargeResult;
import com.sentinelpay.payment.application.port.RiskEvaluator;
import com.sentinelpay.payment.domain.PaymentStatus;
import com.sentinelpay.payment.domain.Provider;
import com.sentinelpay.payment.infrastructure.outbox.PaymentOutboxEntity;
import com.sentinelpay.payment.infrastructure.outbox.PaymentOutboxRepository;
import com.sentinelpay.payment.infrastructure.persistence.IdempotencyKeyEntity;
import com.sentinelpay.payment.infrastructure.persistence.IdempotencyKeyRepository;
import com.sentinelpay.payment.infrastructure.persistence.PaymentAttemptEntity;
import com.sentinelpay.payment.infrastructure.persistence.PaymentAttemptRepository;
import com.sentinelpay.payment.infrastructure.persistence.PaymentEntity;
import com.sentinelpay.payment.infrastructure.persistence.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class ChargeServiceIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Container
    static KafkaContainer kafka =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

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

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @BeforeEach
    void cleanDatabase() {
        paymentOutboxRepository.deleteAll();
        paymentAttemptRepository.deleteAll();
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
