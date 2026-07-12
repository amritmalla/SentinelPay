package com.sentinelpay.risk.infrastructure.outbox;

import com.sentinelpay.common.events.EventEnvelope;
import com.sentinelpay.risk.RiskTestContainers;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.task.scheduling.enabled=false",
        "sentinelpay.risk.grpc.port=0"
})
class OutboxRelayIT {

    @Autowired
    RiskOutboxRepository repository;

    @Autowired
    RiskOutboxRelay relay;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", RiskTestContainers.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", RiskTestContainers.POSTGRES::getUsername);
        registry.add("spring.datasource.password", RiskTestContainers.POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", RiskTestContainers.KAFKA::getBootstrapServers);
        registry.add("spring.data.redis.host", RiskTestContainers.REDIS::getHost);
        registry.add("spring.data.redis.port", () -> RiskTestContainers.REDIS.getMappedPort(6379));
    }

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void relay_publishesRiskAssessedWithTransactionIdKey() throws InterruptedException {
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

        for (int attempt = 0; attempt < 10; attempt++) {
            relay.poll();
            if (repository.findById(row.getId()).orElseThrow().getPublishedAt() != null) {
                break;
            }
            Thread.sleep(200);
        }

        assertThat(repository.findById(row.getId()).orElseThrow().getPublishedAt()).isNotNull();

        try (KafkaConsumer<String, EventEnvelope> consumer = createConsumer()) {
            consumer.subscribe(List.of("risk.assessed"));
            long deadline = System.currentTimeMillis() + 15_000;
            boolean found = false;
            while (System.currentTimeMillis() < deadline && !found) {
                ConsumerRecords<String, EventEnvelope> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, EventEnvelope> record : records) {
                    if (transactionId.toString().equals(record.key())) {
                        assertThat(record.value().correlationId()).isEqualTo("corr-risk-1");
                        found = true;
                        break;
                    }
                }
            }
            assertThat(found).isTrue();
        }
    }

    private KafkaConsumer<String, EventEnvelope> createConsumer() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, RiskTestContainers.KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "risk-outbox-it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.sentinelpay.common.events");
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, EventEnvelope.class.getName());
        return new KafkaConsumer<>(props);
    }
}
