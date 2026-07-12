package com.sentinelpay.common.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelpay.common.events.EventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * Publishes claimed outbox rows to Kafka. {@code published_at} is set only after the broker ack callback
 * completes — never mark-then-send.
 */
public final class OutboxRelayWorker {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayWorker.class);

    private final OutboxEnvelopeMapper envelopeMapper;
    private final ObjectMapper objectMapper;
    private final EventPublisher eventPublisher;
    private final String source;
    private final String tableName;

    public OutboxRelayWorker(
            OutboxEnvelopeMapper envelopeMapper,
            ObjectMapper objectMapper,
            EventPublisher eventPublisher,
            String source,
            String tableName) {
        this.envelopeMapper = envelopeMapper;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.source = source;
        this.tableName = tableName;
    }

    public void publishBatch(List<OutboxEntry> rows, BiConsumer<Long, EventEnvelope> markPublished) {
        for (OutboxEntry row : rows) {
            EventEnvelope envelope = envelopeMapper.toEnvelope(source, tableName, row);
            String topic = row.eventType();
            String key = OutboxPartitionKeys.resolve(row.eventType(), row.payloadJson(), row.aggregateId(), objectMapper);
            eventPublisher.publish(topic, key, envelope);
            markPublished.accept(row.id(), envelope);
            log.debug("Published outbox row {} to topic {} key {}", row.id(), topic, key);
        }
    }

    @FunctionalInterface
    public interface EventPublisher {
        void publish(String topic, String key, EventEnvelope envelope);
    }
}
