package com.sentinelpay.provider.infrastructure.outbox;

import com.sentinelpay.common.outbox.OutboxEntry;
import com.sentinelpay.common.outbox.OutboxRelayWorker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class ProviderOutboxRelay {

    private final ProviderOutboxRepository repository;
    private final OutboxRelayWorker relayWorker;
    private final int batchSize;

    public ProviderOutboxRelay(
            ProviderOutboxRepository repository,
            OutboxRelayWorker providerOutboxRelayWorker,
            @Value("${sentinelpay.outbox.relay.batch-size:50}") int batchSize) {
        this.repository = repository;
        this.relayWorker = providerOutboxRelayWorker;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${sentinelpay.outbox.relay.fixed-delay-ms:500}")
    @Transactional
    public void poll() {
        List<ProviderOutboxEntity> rows = repository.claimUnpublished(batchSize);
        if (rows.isEmpty()) {
            return;
        }
        List<OutboxEntry> entries = rows.stream().map(ProviderOutboxRelay::toEntry).toList();
        relayWorker.publishBatch(
                entries, (id, envelope) -> repository.markPublished(id, Instant.now()));
    }

    private static OutboxEntry toEntry(ProviderOutboxEntity entity) {
        return new OutboxEntry(
                entity.getId(),
                entity.getAggregate(),
                entity.getAggregateId(),
                entity.getEventType(),
                entity.getPayload(),
                entity.getCreatedAt());
    }
}
