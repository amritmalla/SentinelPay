package com.sentinelpay.risk.infrastructure.outbox;

import com.sentinelpay.common.events.EventEnvelope;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class OutboxRelayIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Container
    static KafkaContainer kafka =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @Autowired
    RiskOutboxRepository repository;

    @Autowired
    RiskOutboxRelay relay;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> 6399);
    }

    @Test
    void relay_publishesRiskAssessedWithTransactionIdKey() {
        UUID transactionId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        RiskOutboxEntity row = new RiskOutboxEntity();
        row.setAggregate("risk_assessment");
        row.setAggregateId(transactionId);
        row.setEventType("risk.assessed");
        row.setPayload(
                """
                {
                  "transactionId": "%s",
                  "merchantId": "%s",
                  "correlationId": "corr-risk-1",
                  "score": 0.15
                }
                """
                        .formatted(transactionId, merchantId));
        row = repository.saveAndFlush(row);

        relay.poll();

        assertThat(repository.findById(row.getId()).orElseThrow().getPublishedAt()).isNotNull();

        try (KafkaConsumer<String, EventEnvelope> consumer = createConsumer()) {
            consumer.subscribe(List.of("risk.assessed"));
            ConsumerRecords<String, EventEnvelope> records = consumer.poll(Duration.ofSeconds(15));
            assertThat(records.count()).isGreaterThanOrEqualTo(1);
            ConsumerRecord<String, EventEnvelope> record = records.iterator().next();
            assertThat(record.key()).isEqualTo(transactionId.toString());
            assertThat(record.value().correlationId()).isEqualTo("corr-risk-1");
        }
    }

    private KafkaConsumer<String, EventEnvelope> createConsumer() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "risk-outbox-it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.sentinelpay.common.events");
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, EventEnvelope.class.getName());
        return new KafkaConsumer<>(props);
    }
}
