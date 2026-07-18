package com.sentinelpay.payment.application;

import com.sentinelpay.payment.PaymentTestContainers;
import com.sentinelpay.payment.domain.PaymentStatus;
import com.sentinelpay.payment.infrastructure.persistence.PaymentAttemptEntity;
import com.sentinelpay.payment.infrastructure.persistence.PaymentAttemptRepository;
import com.sentinelpay.payment.infrastructure.persistence.PaymentEntity;
import com.sentinelpay.payment.infrastructure.persistence.PaymentRepository;
import com.sentinelpay.payment.infrastructure.persistence.PaymentStatusHistoryRepository;
import com.sentinelpay.payment.infrastructure.persistence.ProcessedWebhookEventRepository;
import com.sentinelpay.payment.support.PaymentDatabaseReset;
import com.sentinelpay.payment.support.StripeWebhookTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
class WebhookServiceIT {

    private static final String WEBHOOK_SECRET = "whsec_test_signing_secret";

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
    DataSource dataSource;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PaymentTestContainers.register(registry);
        registry.add("sentinelpay.providers.stripe.webhook-secret", () -> WEBHOOK_SECRET);
    }

    @BeforeEach
    void clean() {
        PaymentDatabaseReset.truncateAll(dataSource);
    }

    @Test
    void handle_paymentIntentSucceededOnCompleted_isNoOp() {
        UUID paymentId = seedCompletedPayment("pi_completed_1");
        String payload = """
                {
                  "id": "evt_1",
                  "type": "payment_intent.succeeded",
                  "data": { "object": { "id": "pi_completed_1" } }
                }
                """;

        webhookService.handle(payload, sign(payload));

        assertThat(processedWebhookEventRepository.count()).isOne();
        assertThat(paymentRepository.findById(paymentId).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.COMPLETED);
    }

    @Test
    void handle_duplicateEvent_isIdempotent() {
        seedCompletedPayment("pi_dup_1");
        String payload = """
                {
                  "id": "evt_dup",
                  "type": "payment_intent.succeeded",
                  "data": { "object": { "id": "pi_dup_1" } }
                }
                """;

        webhookService.handle(payload, sign(payload));
        webhookService.handle(payload, sign(payload));

        assertThat(processedWebhookEventRepository.count()).isOne();
    }

    @Test
    void handle_unknownProviderRef_isAcknowledgedNoOp() {
        String payload = """
                {
                  "id": "evt_unknown",
                  "type": "payment_intent.succeeded",
                  "data": { "object": { "id": "pi_missing" } }
                }
                """;

        webhookService.handle(payload, sign(payload));

        assertThat(processedWebhookEventRepository.count()).isOne();
    }

    @Test
    void handle_chargeRefunded_marksPaymentRefunded() {
        UUID paymentId = seedCompletedPayment("pi_refund_1");
        String payload = """
                {
                  "id": "evt_refund",
                  "type": "charge.refunded",
                  "data": { "object": { "id": "pi_refund_1" } }
                }
                """;

        webhookService.handle(payload, sign(payload));

        assertThat(paymentRepository.findById(paymentId).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void handle_chargeRefunded_duplicate_isIdempotent() {
        UUID paymentId = seedCompletedPayment("pi_refund_dup");
        String payload = """
                {
                  "id": "evt_refund_dup",
                  "type": "charge.refunded",
                  "data": { "object": { "id": "pi_refund_dup" } }
                }
                """;

        webhookService.handle(payload, sign(payload));
        webhookService.handle(payload, sign(payload));

        assertThat(paymentRepository.findById(paymentId).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.REFUNDED);
        assertThat(processedWebhookEventRepository.count()).isOne();
    }

    private String sign(String payload) {
        return StripeWebhookTestSupport.sign(payload, WEBHOOK_SECRET);
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
