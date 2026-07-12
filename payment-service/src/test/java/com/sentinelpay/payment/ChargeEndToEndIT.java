package com.sentinelpay.payment;

import com.sentinelpay.common.events.EventEnvelope;
import com.sentinelpay.payment.application.port.RiskEvaluator;
import com.sentinelpay.payment.infrastructure.outbox.PaymentOutboxEntity;
import com.sentinelpay.payment.infrastructure.outbox.PaymentOutboxRepository;
import com.sentinelpay.payment.infrastructure.outbox.PaymentOutboxRelay;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
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
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ChargeEndToEndIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Container
    static KafkaContainer kafka =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @Autowired
    MockMvc mockMvc;

    @Autowired
    PaymentOutboxRepository outboxRepository;

    @Autowired
    PaymentOutboxRelay relay;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
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

        relay.poll();

        PaymentOutboxEntity published = outboxRepository.findById(outboxRows.get(0).getId()).orElseThrow();
        assertThat(published.getPublishedAt()).isNotNull();

        try (KafkaConsumer<String, EventEnvelope> consumer = createConsumer()) {
            consumer.subscribe(List.of("payment.completed"));
            ConsumerRecords<String, EventEnvelope> records = consumer.poll(Duration.ofSeconds(15));
            assertThat(records.count()).isGreaterThanOrEqualTo(1);

            ConsumerRecord<String, EventEnvelope> record = records.iterator().next();
            assertThat(record.value().eventType()).isEqualTo("payment.completed");
            assertThat(record.value().correlationId()).isEqualTo("corr-e2e");
            assertThat(record.key()).isEqualTo(merchantId.toString());
        }
    }

    private KafkaConsumer<String, EventEnvelope> createConsumer() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
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
