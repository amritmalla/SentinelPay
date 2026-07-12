package com.sentinelpay.common.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Standard Kafka event envelope shared by all producing and consuming services (ADR-0008).
 */
public record EventEnvelope(
        UUID eventId,
        String eventType,
        Instant timestamp,
        String source,
        String correlationId,
        TenantContext tenantContext,
        Object data) {
}
