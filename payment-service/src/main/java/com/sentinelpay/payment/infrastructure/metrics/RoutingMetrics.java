package com.sentinelpay.payment.infrastructure.metrics;

import com.sentinelpay.payment.application.routing.RoutingDecision;
import com.sentinelpay.payment.domain.Provider;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class RoutingMetrics {

    private final MeterRegistry registry;
    private final Counter fallbackCounter;
    private final Counter breakerBypassCounter;
    private final Map<Provider, AtomicReference<Double>> lastScores = new ConcurrentHashMap<>();

    public RoutingMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.fallbackCounter = Counter.builder("sentinelpay_routing_fallback_total")
                .description("Routing decisions that fell back to static order")
                .register(registry);
        this.breakerBypassCounter = Counter.builder("sentinelpay_routing_breaker_bypass_total")
                .description("Never-strand guardrail: all breakers open, filtering skipped")
                .register(registry);
        for (Provider provider : Provider.values()) {
            AtomicReference<Double> holder = new AtomicReference<>(0.0);
            lastScores.put(provider, holder);
            Gauge.builder("sentinelpay_routing_provider_score", holder, ref -> ref.get())
                    .tag("provider", provider.dbValue())
                    .description("Last computed routing score or posterior mean")
                    .register(registry);
        }
    }

    public void recordDecision(RoutingDecision decision, String reason) {
        String first = decision.firstChoice() == null ? "none" : decision.firstChoice().dbValue();
        Counter.builder("sentinelpay_routing_decision_total")
                .tag("first_choice", first)
                .tag("policy", decision.policy())
                .tag("reason", reason)
                .description("Routing decisions by first choice and policy")
                .register(registry)
                .increment();
    }

    public void recordProviderScore(Provider provider, double score) {
        lastScores.computeIfAbsent(provider, p -> new AtomicReference<>(0.0)).set(score);
    }

    public void recordFallback() {
        fallbackCounter.increment();
    }

    public void recordBreakerBypass() {
        breakerBypassCounter.increment();
    }
}
