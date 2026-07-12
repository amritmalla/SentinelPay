package com.sentinelpay.payment.infrastructure.outbox;

import com.sentinelpay.common.events.EventEnvelope;
import com.sentinelpay.common.outbox.OutboxEnvelopeMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class OutboxRelayIT {

    private static final DockerImageName KAFKA_IMAGE =
            DockerImageName.parse("confluentinc/cp-kafka:7.6.0");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Container
    static KafkaContainer kafka = new KafkaContainer(KAFKA_IMAGE);

    @Autowired
    PaymentOutboxRepository repository;

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
    void relay_publishesToKafkaAndMarksPublishedAt() {
        UUID merchantId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        String payload =
                """
                {
                  "merchantId": "%s",
                  "paymentId": "%s",
                  "correlationId": "corr-test-1",
                  "amountCents": 1000
                }
                """
                        .formatted(merchantId, paymentId);

        PaymentOutboxEntity row = new PaymentOutboxEntity();
        row.setAggregate("payment");
        row.setAggregateId(paymentId);
        row.setEventType("payment.completed");
        row.setPayload(payload);
        row = repository.saveAndFlush(row);

        UUID expectedEventId = OutboxEnvelopeMapper.stableEventId("payment_outbox", row.getId());

        relay.poll();

        PaymentOutboxEntity published = repository.findById(row.getId()).orElseThrow();
        assertThat(published.getPublishedAt()).isNotNull();

        try (KafkaConsumer<String, EventEnvelope> consumer = createConsumer()) {
            consumer.subscribe(List.of("payment.completed"));
            ConsumerRecords<String, EventEnvelope> records = consumer.poll(Duration.ofSeconds(15));
            assertThat(records.count()).isGreaterThanOrEqualTo(1);

            ConsumerRecord<String, EventEnvelope> record = records.iterator().next();
            EventEnvelope envelope = record.value();
            assertThat(envelope.eventId()).isEqualTo(expectedEventId);
            assertThat(envelope.eventType()).isEqualTo("payment.completed");
            assertThat(envelope.source()).isEqualTo("payment-service");
            assertThat(envelope.correlationId()).isEqualTo("corr-test-1");
            assertThat(envelope.tenantContext().merchantId()).isEqualTo(merchantId);
            assertThat(record.key()).isEqualTo(merchantId.toString());
        }
    }

    @Test
    void relay_republishUsesSameEventId() {
        UUID merchantId = UUID.randomUUID();
        PaymentOutboxEntity row = new PaymentOutboxEntity();
        row.setAggregate("payment");
        row.setAggregateId(UUID.randomUUID());
        row.setEventType("payment.failed");
        row.setPayload("{\"merchantId\": \"%s\", \"correlationId\": \"corr-2\"}".formatted(merchantId));
        row = repository.saveAndFlush(row);

        UUID expectedEventId = OutboxEnvelopeMapper.stableEventId("payment_outbox", row.getId());

        relay.poll();

        row = repository.findById(row.getId()).orElseThrow();
        row.setPublishedAt(null);
        repository.saveAndFlush(row);

        relay.poll();

        try (KafkaConsumer<String, EventEnvelope> consumer = createConsumer()) {
            consumer.subscribe(List.of("payment.failed"));
            List<EventEnvelope> envelopes = new ArrayList<>();
            long deadline = System.currentTimeMillis() + 15_000;
            while (envelopes.size() < 2 && System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, EventEnvelope> records = consumer.poll(Duration.ofMillis(500));
                records.forEach(r -> envelopes.add(r.value()));
            }
            assertThat(envelopes).hasSizeGreaterThanOrEqualTo(2);
            assertThat(envelopes).allMatch(e -> e.eventId().equals(expectedEventId));
        }
    }

    private KafkaConsumer<String, EventEnvelope> createConsumer() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "outbox-relay-it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.sentinelpay.common.events");
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, EventEnvelope.class.getName());
        return new KafkaConsumer<>(props);
    }
}
