package com.sentinelpay.risk.infrastructure.outbox;

import com.sentinelpay.common.outbox.OutboxEntry;
import com.sentinelpay.common.outbox.OutboxRelayWorker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class RiskOutboxRelay {

    private final RiskOutboxRepository repository;
    private final OutboxRelayWorker relayWorker;
    private final int batchSize;

    public RiskOutboxRelay(
            RiskOutboxRepository repository,
            OutboxRelayWorker riskOutboxRelayWorker,
            @Value("${sentinelpay.outbox.relay.batch-size:50}") int batchSize) {
        this.repository = repository;
        this.relayWorker = riskOutboxRelayWorker;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${sentinelpay.outbox.relay.fixed-delay-ms:500}")
    @Transactional
    public void poll() {
        List<RiskOutboxEntity> rows = repository.claimUnpublished(batchSize);
        if (rows.isEmpty()) {
            return;
        }
        List<OutboxEntry> entries = rows.stream().map(RiskOutboxRelay::toEntry).toList();
        relayWorker.publishBatch(
                entries, (id, envelope) -> repository.markPublished(id, Instant.now()));
    }

    private static OutboxEntry toEntry(RiskOutboxEntity entity) {
        return new OutboxEntry(
                entity.getId(),
                entity.getAggregate(),
                entity.getAggregateId(),
                entity.getEventType(),
                entity.getPayload(),
                entity.getCreatedAt());
    }
}
