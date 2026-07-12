package com.sentinelpay.payment.infrastructure.outbox;

import com.sentinelpay.common.events.EventEnvelope;
import com.sentinelpay.common.outbox.OutboxEnvelopeMapper;
import com.sentinelpay.payment.PaymentTestContainers;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
class OutboxRelayIT {

    @Autowired
    PaymentOutboxRepository repository;

    @Autowired
    PaymentOutboxRelay relay;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PaymentTestContainers.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", PaymentTestContainers.POSTGRES::getUsername);
        registry.add("spring.datasource.password", PaymentTestContainers.POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", PaymentTestContainers.KAFKA::getBootstrapServers);
    }

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void relay_publishesToKafkaAndMarksPublishedAt() throws InterruptedException {
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

        for (int attempt = 0; attempt < 10; attempt++) {
            relay.poll();
            PaymentOutboxEntity published = repository.findById(row.getId()).orElseThrow();
            if (published.getPublishedAt() != null) {
                break;
            }
            Thread.sleep(200);
        }

        PaymentOutboxEntity published = repository.findById(row.getId()).orElseThrow();
        assertThat(published.getPublishedAt()).isNotNull();

        try (KafkaConsumer<String, EventEnvelope> consumer = createConsumer()) {
            consumer.subscribe(List.of("payment.completed"));
            EventEnvelope envelope = awaitEnvelope(consumer, expectedEventId);
            assertThat(envelope.eventType()).isEqualTo("payment.completed");
            assertThat(envelope.source()).isEqualTo("payment-service");
            assertThat(envelope.correlationId()).isEqualTo("corr-test-1");
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
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, EventEnvelope> records = consumer.poll(Duration.ofMillis(500));
                records.forEach(r -> {
                    if (r.value().eventId().equals(expectedEventId)) {
                        envelopes.add(r.value());
                    }
                });
                if (envelopes.size() >= 2) {
                    break;
                }
            }
            assertThat(envelopes).hasSizeGreaterThanOrEqualTo(2);
            assertThat(envelopes).allMatch(e -> e.eventId().equals(expectedEventId));
        }
    }

    private KafkaConsumer<String, EventEnvelope> createConsumer() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, PaymentTestContainers.KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "outbox-relay-it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.sentinelpay.common.events");
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, EventEnvelope.class.getName());
        return new KafkaConsumer<>(props);
    }
}
