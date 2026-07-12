package com.sentinelpay.common.outbox;

import java.time.Instant;
import java.util.UUID;

/** Row claimed from an {@code *_outbox} table by the relay poller. */
public record OutboxEntry(
        long id,
        String aggregate,
        UUID aggregateId,
        String eventType,
        String payloadJson,
        Instant createdAt) {
}
