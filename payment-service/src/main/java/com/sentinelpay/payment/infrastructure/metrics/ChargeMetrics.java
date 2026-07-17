package com.sentinelpay.payment.infrastructure.metrics;

import com.sentinelpay.payment.domain.Provider;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Component
public class ChargeMetrics {

    private final MeterRegistry registry;
    private final Timer chargeDuration;
    private final Counter doubleCapture;
    private final Counter riskFallback;

    public ChargeMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.chargeDuration = Timer.builder("sentinelpay_charge_duration_seconds")
                .description("End-to-end charge path latency")
                .register(registry);
        this.doubleCapture = Counter.builder("sentinelpay_double_capture_total")
                .description("Payments captured when already in a captured/terminal state")
                .register(registry);
        this.riskFallback = Counter.builder("sentinelpay_risk_fallback_total")
                .description("Risk evaluations that used the payment-side fallback")
                .register(registry);
    }

    public <T> T recordCharge(Supplier<T> charge) {
        return chargeDuration.record(charge);
    }

    public void recordRecoveredAuthorization(Provider provider) {
        Counter.builder("sentinelpay_recovered_authorization_total")
                .tag("provider", provider.dbValue())
                .register(registry)
                .increment();
    }

    public void recordDoubleCapture() {
        doubleCapture.increment();
    }

    public void recordRiskFallback() {
        riskFallback.increment();
    }

    public void recordProviderAuthorization(Provider provider, String outcome) {
        Counter.builder("sentinelpay_provider_authorization_total")
                .tag("provider", provider.dbValue())
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }

    public <T> T recordProviderCall(Provider provider, String op, Supplier<T> call) {
        return recordProviderCallTimed(provider, op, call).value();
    }

    public <T> TimedResult<T> recordProviderCallTimed(Provider provider, String op, Supplier<T> call) {
        Timer timer = Timer.builder("sentinelpay_provider_call_duration_seconds")
                .tag("provider", provider.dbValue())
                .tag("op", op)
                .register(registry);
        long start = System.nanoTime();
        try {
            return new TimedResult<>(call.get(), elapsedMillis(start));
        } finally {
            timer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    private static long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    public record TimedResult<T>(T value, long elapsedMs) {
    }
}
