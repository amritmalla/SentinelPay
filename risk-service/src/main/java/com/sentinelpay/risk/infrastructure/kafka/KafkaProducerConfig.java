package com.sentinelpay.risk.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelpay.common.events.EventEnvelope;
import com.sentinelpay.common.outbox.OutboxEnvelopeMapper;
import com.sentinelpay.common.outbox.OutboxRelayWorker;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

@Configuration
@EnableScheduling
public class KafkaProducerConfig {

    @Bean
    ProducerFactory<String, EventEnvelope> eventProducerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    KafkaTemplate<String, EventEnvelope> eventKafkaTemplate(
            ProducerFactory<String, EventEnvelope> eventProducerFactory) {
        return new KafkaTemplate<>(eventProducerFactory);
    }

    @Bean
    OutboxRelayWorker.EventPublisher kafkaEventPublisher(KafkaTemplate<String, EventEnvelope> eventKafkaTemplate) {
        return (topic, key, envelope) -> {
            try {
                eventKafkaTemplate.send(topic, key, envelope).get();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted waiting for Kafka broker ack on topic " + topic, ex);
            } catch (ExecutionException ex) {
                throw new IllegalStateException("Kafka publish failed for topic " + topic, ex.getCause());
            }
        };
    }

    @Bean
    OutboxRelayWorker riskOutboxRelayWorker(
            OutboxEnvelopeMapper envelopeMapper,
            ObjectMapper objectMapper,
            OutboxRelayWorker.EventPublisher kafkaEventPublisher) {
        return new OutboxRelayWorker(
                envelopeMapper, objectMapper, kafkaEventPublisher, "risk-service", "risk_outbox");
    }
}
