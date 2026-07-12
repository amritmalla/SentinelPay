package com.sentinelpay.payment.infrastructure.persistence;

import com.sentinelpay.payment.domain.PaymentStatus;
import com.sentinelpay.payment.infrastructure.persistence.IdempotencyKeyId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class PaymentPersistenceIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Container
    static KafkaContainer kafka =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @Autowired
    PaymentRepository paymentRepository;

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
}
