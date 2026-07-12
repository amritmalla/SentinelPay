package com.sentinelpay.common.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelpay.common.events.EventEnvelope;
import com.sentinelpay.common.events.TenantContext;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * Builds a shared {@link EventEnvelope} from an outbox row. {@code eventId} is stable per outbox row so
 * at-least-once republication dedups correctly downstream.
 */
@Component
public class OutboxEnvelopeMapper {

    private final ObjectMapper objectMapper;

    public OutboxEnvelopeMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public EventEnvelope toEnvelope(String source, String tableName, OutboxEntry row) {
        JsonNode payload = parsePayload(row.payloadJson());
        UUID eventId = stableEventId(tableName, row.id());
        String correlationId = textField(payload, "correlationId");
        TenantContext tenantContext = tenantFromPayload(payload);
        Object data = dataFromPayload(row.payloadJson());
        return new EventEnvelope(
                eventId,
                row.eventType(),
                row.createdAt(),
                source,
                correlationId,
                tenantContext,
                data);
    }

    public static UUID stableEventId(String tableName, long outboxId) {
        return UUID.nameUUIDFromBytes((tableName + ":" + outboxId).getBytes(StandardCharsets.UTF_8));
    }

    private JsonNode parsePayload(String payloadJson) {
        try {
            return objectMapper.readTree(payloadJson);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Invalid outbox payload JSON for row", ex);
        }
    }

    private Object dataFromPayload(String payloadJson) {
        try {
            return objectMapper.readValue(payloadJson, Object.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Invalid outbox payload JSON for row", ex);
        }
    }

    private static TenantContext tenantFromPayload(JsonNode payload) {
        String merchantId = textField(payload, "merchantId");
        if (merchantId == null) {
            return null;
        }
        return new TenantContext(UUID.fromString(merchantId));
    }

    private static String textField(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        return node.asText();
    }
}
