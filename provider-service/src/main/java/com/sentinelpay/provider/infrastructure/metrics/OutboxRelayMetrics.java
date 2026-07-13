package com.sentinelpay.provider.infrastructure.metrics;

import com.sentinelpay.provider.infrastructure.outbox.ProviderOutboxRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OutboxRelayMetrics {

    private final MeterRegistry registry;
    private final ProviderOutboxRepository outboxRepository;
    private final String serviceName;

    public OutboxRelayMetrics(
            MeterRegistry registry,
            ProviderOutboxRepository outboxRepository,
            @Value("${spring.application.name}") String serviceName) {
        this.registry = registry;
        this.outboxRepository = outboxRepository;
        this.serviceName = serviceName;
    }

    @PostConstruct
    void registerGauges() {
        Gauge.builder("sentinelpay_outbox_pending", outboxRepository, ProviderOutboxRepository::countPending)
                .tag("service", serviceName)
                .register(registry);
        Gauge.builder("sentinelpay_outbox_oldest_pending_seconds", this, OutboxRelayMetrics::oldestPendingSeconds)
                .tag("service", serviceName)
                .register(registry);
    }

    private double oldestPendingSeconds() {
        Double age = outboxRepository.oldestPendingAgeSeconds();
        return age != null ? age : 0.0;
    }
}
