package com.sentinelpay.common.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.UUID;

/** Resolves Kafka partition keys per the backend-architecture event catalog. */
public final class OutboxPartitionKeys {

    private OutboxPartitionKeys() {
    }

    public static String resolve(String eventType, String payloadJson, UUID aggregateId, ObjectMapper objectMapper) {
        JsonNode payload = parse(payloadJson, objectMapper);
        return switch (eventType) {
            case "payment.completed", "payment.failed", "payment.refunded", "fraud.alert.high" ->
                    textOrDefault(payload, "merchantId", aggregateId.toString());
            case "risk.assessed" -> textOrDefault(payload, "transactionId", aggregateId.toString());
            case "provider.health.changed" -> textOrDefault(payload, "provider", aggregateId.toString());
            default -> aggregateId.toString();
        };
    }

    private static JsonNode parse(String payloadJson, ObjectMapper objectMapper) {
        try {
            return objectMapper.readTree(payloadJson);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Invalid outbox payload JSON", ex);
        }
    }

    private static String textOrDefault(JsonNode payload, String field, String defaultValue) {
        JsonNode node = payload.get(field);
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        return node.asText();
    }
}
