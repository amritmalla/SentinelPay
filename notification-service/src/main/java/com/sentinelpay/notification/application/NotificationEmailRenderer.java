package com.sentinelpay.notification.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelpay.common.events.EventEnvelope;
import org.springframework.stereotype.Component;

@Component
public class NotificationEmailRenderer {

    private final ObjectMapper objectMapper;

    public NotificationEmailRenderer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public RenderedEmail render(EventEnvelope envelope) {
        JsonNode data = objectMapper.valueToTree(envelope.data());
        return switch (envelope.eventType()) {
            case "payment.completed" -> new RenderedEmail(
                    "Payment completed",
                    "Payment %s completed successfully."
                            .formatted(text(data, "paymentId", envelope.correlationId())));
            case "payment.failed" -> new RenderedEmail(
                    "Payment failed",
                    "Payment %s failed: %s"
                            .formatted(
                                    text(data, "paymentId", "unknown"),
                                    text(data, "reason", "no reason provided")));
            case "payment.refunded" -> new RenderedEmail(
                    "Payment refunded",
                    "Refund %s for payment %s was processed."
                            .formatted(
                                    text(data, "refundId", "unknown"),
                                    text(data, "paymentId", "unknown")));
            case "fraud.alert.high" -> new RenderedEmail(
                    "High fraud alert",
                    "Transaction %s flagged with score %s."
                            .formatted(
                                    text(data, "transactionId", "unknown"),
                                    text(data, "score", "unknown")));
            default -> new RenderedEmail(
                    "SentinelPay notification",
                    "Event %s received.".formatted(envelope.eventType()));
        };
    }

    public String resolveRecipient(EventEnvelope envelope) {
        JsonNode data = objectMapper.valueToTree(envelope.data());
        String recipient = text(data, "customerEmail", null);
        if (recipient == null) {
            recipient = text(data, "recipient", null);
        }
        if (recipient == null) {
            throw new IllegalArgumentException(
                    "No recipient in event payload for eventId " + envelope.eventId());
        }
        return recipient;
    }

    private static String text(JsonNode data, String field, String defaultValue) {
        JsonNode node = data.get(field);
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        return node.asText();
    }

    public record RenderedEmail(String subject, String body) {
    }
}
