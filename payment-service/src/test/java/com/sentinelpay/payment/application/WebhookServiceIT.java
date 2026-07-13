package com.sentinelpay.payment.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelpay.payment.PaymentTestContainers;
import com.sentinelpay.payment.domain.PaymentStatus;
import com.sentinelpay.payment.infrastructure.persistence.PaymentAttemptEntity;
import com.sentinelpay.payment.infrastructure.persistence.PaymentAttemptRepository;
import com.sentinelpay.payment.infrastructure.persistence.PaymentEntity;
import com.sentinelpay.payment.infrastructure.persistence.PaymentRepository;
import com.sentinelpay.payment.infrastructure.persistence.PaymentStatusHistoryRepository;
import com.sentinelpay.payment.infrastructure.persistence.ProcessedWebhookEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
class WebhookServiceIT {

    @Autowired
    WebhookService webhookService;

    @Autowired
    PaymentRepository paymentRepository;

    @Autowired
    PaymentAttemptRepository paymentAttemptRepository;

    @Autowired
    PaymentStatusHistoryRepository paymentStatusHistoryRepository;

    @Autowired
    ProcessedWebhookEventRepository processedWebhookEventRepository;

    @Autowired
    ObjectMapper objectMapper;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PaymentTestContainers.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", PaymentTestContainers.POSTGRES::getUsername);
        registry.add("spring.datasource.password", PaymentTestContainers.POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", PaymentTestContainers.KAFKA::getBootstrapServers);
    }

    @BeforeEach
    void clean() {
        processedWebhookEventRepository.deleteAll();
        paymentAttemptRepository.deleteAll();
        paymentStatusHistoryRepository.deleteAll();
        paymentRepository.deleteAll();
    }

    @Test
    void handle_paymentIntentSucceededOnCompleted_isNoOp() throws Exception {
        UUID paymentId = seedCompletedPayment("pi_completed_1");

        webhookService.handle(objectMapper.readTree("""
                {
                  "id": "evt_1",
                  "type": "payment_intent.succeeded",
                  "data": { "object": { "id": "pi_completed_1" } }
                }
                """));

        assertThat(processedWebhookEventRepository.count()).isOne();
        assertThat(paymentRepository.findById(paymentId).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.COMPLETED);
    }

    @Test
    void handle_duplicateEvent_isIdempotent() throws Exception {
        seedCompletedPayment("pi_dup_1");
        String payload = """
                {
                  "id": "evt_dup",
                  "type": "payment_intent.succeeded",
                  "data": { "object": { "id": "pi_dup_1" } }
                }
                """;

        webhookService.handle(objectMapper.readTree(payload));
        webhookService.handle(objectMapper.readTree(payload));

        assertThat(processedWebhookEventRepository.count()).isOne();
    }

    @Test
    void handle_unknownProviderRef_isAcknowledgedNoOp() throws Exception {
        webhookService.handle(objectMapper.readTree("""
                {
                  "id": "evt_unknown",
                  "type": "payment_intent.succeeded",
                  "data": { "object": { "id": "pi_missing" } }
                }
                """));

        assertThat(processedWebhookEventRepository.count()).isOne();
    }

    @Test
    void handle_chargeRefunded_marksPaymentRefunded() throws Exception {
        UUID paymentId = seedCompletedPayment("pi_refund_1");

        webhookService.handle(objectMapper.readTree("""
                {
                  "id": "evt_refund",
                  "type": "charge.refunded",
                  "data": { "object": { "id": "pi_refund_1" } }
                }
                """));

        assertThat(paymentRepository.findById(paymentId).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void handle_chargeRefunded_duplicate_isIdempotent() throws Exception {
        UUID paymentId = seedCompletedPayment("pi_refund_dup");
        String payload = """
                {
                  "id": "evt_refund_dup",
                  "type": "charge.refunded",
                  "data": { "object": { "id": "pi_refund_dup" } }
                }
                """;

        webhookService.handle(objectMapper.readTree(payload));
        webhookService.handle(objectMapper.readTree(payload));

        assertThat(paymentRepository.findById(paymentId).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.REFUNDED);
        assertThat(processedWebhookEventRepository.count()).isOne();
    }

    private UUID seedCompletedPayment(String providerRef) {
        PaymentEntity payment = new PaymentEntity();
        payment.setMerchantId(UUID.randomUUID());
        payment.setStatus(PaymentStatus.COMPLETED);
        payment.setAmountCents(2_500);
        payment.setCurrency("USD");
        payment.setProvider("stripe");
        payment = paymentRepository.saveAndFlush(payment);

        PaymentAttemptEntity attempt = new PaymentAttemptEntity();
        attempt.setPaymentId(payment.getId());
        attempt.setAttemptNumber((short) 1);
        attempt.setProvider("stripe");
        attempt.setOutcome("CAPTURED");
        attempt.setProviderRef(providerRef);
        paymentAttemptRepository.saveAndFlush(attempt);

        return payment.getId();
    }
}
