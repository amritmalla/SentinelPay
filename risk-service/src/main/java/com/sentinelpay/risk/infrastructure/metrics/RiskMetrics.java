package com.sentinelpay.risk.infrastructure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class RiskMetrics {

    private final MeterRegistry registry;
    private final Counter riskFallback;

    public RiskMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.riskFallback = Counter.builder("sentinelpay_risk_fallback_total")
                .description("Risk assessments served from persisted fallback results")
                .register(registry);
    }

    public void recordDecision(String recommendation) {
        Counter.builder("sentinelpay_risk_decision_total")
                .tag("recommendation", recommendation)
                .register(registry)
                .increment();
    }

    public void recordFallback() {
        riskFallback.increment();
    }
}
