package com.sentinelpay.payment;

import com.sentinelpay.common.events.EventEnvelope;
import com.sentinelpay.common.outbox.OutboxEnvelopeMapper;
import com.sentinelpay.payment.application.port.RiskEvaluator;
import com.sentinelpay.payment.infrastructure.outbox.PaymentOutboxEntity;
import com.sentinelpay.payment.infrastructure.outbox.PaymentOutboxRelay;
import com.sentinelpay.payment.infrastructure.outbox.PaymentOutboxRepository;
import com.sentinelpay.payment.infrastructure.persistence.IdempotencyKeyRepository;
import com.sentinelpay.payment.infrastructure.persistence.PaymentAttemptRepository;
import com.sentinelpay.payment.infrastructure.persistence.PaymentRepository;
import com.sentinelpay.payment.infrastructure.persistence.PaymentStatusHistoryRepository;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
@AutoConfigureMockMvc
class ChargeEndToEndIT {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    PaymentOutboxRepository outboxRepository;

    @Autowired
    PaymentOutboxRelay relay;

    @Autowired
    PaymentRepository paymentRepository;

    @Autowired
    PaymentAttemptRepository paymentAttemptRepository;

    @Autowired
    IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    PaymentStatusHistoryRepository paymentStatusHistoryRepository;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PaymentTestContainers.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", PaymentTestContainers.POSTGRES::getUsername);
        registry.add("spring.datasource.password", PaymentTestContainers.POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", PaymentTestContainers.KAFKA::getBootstrapServers);
    }

    @BeforeEach
    void cleanDatabase() {
        outboxRepository.deleteAll();
        paymentAttemptRepository.deleteAll();
        paymentStatusHistoryRepository.deleteAll();
        idempotencyKeyRepository.deleteAll();
        paymentRepository.deleteAll();
    }

    @Test
    void charge_endToEnd_publishesPaymentCompletedEvent() throws Exception {
        UUID merchantId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/payments/charge")
                        .header("Idempotency-Key", "e2e-1")
                        .header("X-Correlation-Id", "corr-e2e")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "merchant_id": "%s",
                                  "amount_cents": 2500,
                                  "currency": "USD",
                                  "customer_email": "buyer@example.com"
                                }
                                """.formatted(merchantId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.provider").value("MOCKPAY"));

        List<PaymentOutboxEntity> outboxRows = outboxRepository.findAll();
        assertThat(outboxRows).hasSize(1);
        assertThat(outboxRows.get(0).getEventType()).isEqualTo("payment.completed");

        for (int attempt = 0; attempt < 10; attempt++) {
            relay.poll();
            PaymentOutboxEntity published = outboxRepository.findById(outboxRows.get(0).getId()).orElseThrow();
            if (published.getPublishedAt() != null) {
                break;
            }
            Thread.sleep(200);
        }

        PaymentOutboxEntity published = outboxRepository.findById(outboxRows.get(0).getId()).orElseThrow();
        assertThat(published.getPublishedAt()).isNotNull();

        UUID expectedEventId =
                OutboxEnvelopeMapper.stableEventId("payment_outbox", outboxRows.get(0).getId());

        try (KafkaConsumer<String, EventEnvelope> consumer = createConsumer()) {
            consumer.subscribe(List.of("payment.completed"));
            EventEnvelope envelope = awaitEnvelope(consumer, expectedEventId);
            assertThat(envelope.eventType()).isEqualTo("payment.completed");
            assertThat(envelope.correlationId()).isEqualTo("corr-e2e");
            assertThat(envelope.tenantContext().merchantId()).isEqualTo(merchantId);
        }
    }

    private EventEnvelope awaitEnvelope(KafkaConsumer<String, EventEnvelope> consumer, UUID expectedEventId) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, EventEnvelope> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, EventEnvelope> record : records) {
                if (record.value().eventId().equals(expectedEventId)) {
                    return record.value();
                }
            }
        }
        throw new AssertionError("No Kafka record with eventId " + expectedEventId);
    }

    private KafkaConsumer<String, EventEnvelope> createConsumer() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, PaymentTestContainers.KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "charge-e2e-it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.sentinelpay.common.events");
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, EventEnvelope.class.getName());
        return new KafkaConsumer<>(props);
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
