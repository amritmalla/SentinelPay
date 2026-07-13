package com.sentinelpay.risk.infrastructure.metrics;

import com.sentinelpay.risk.infrastructure.outbox.RiskOutboxRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OutboxRelayMetrics {

    private final MeterRegistry registry;
    private final RiskOutboxRepository outboxRepository;
    private final String serviceName;

    public OutboxRelayMetrics(
            MeterRegistry registry,
            RiskOutboxRepository outboxRepository,
            @Value("${spring.application.name}") String serviceName) {
        this.registry = registry;
        this.outboxRepository = outboxRepository;
        this.serviceName = serviceName;
    }

    @PostConstruct
    void registerGauges() {
        Gauge.builder("sentinelpay_outbox_pending", outboxRepository, RiskOutboxRepository::countPending)
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
