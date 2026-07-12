package com.sentinelpay.payment.infrastructure.provider;

import com.sentinelpay.payment.application.model.ProviderBehavior;
import com.sentinelpay.payment.application.model.ProviderBehavior.ProgrammedOutcome;
import com.sentinelpay.payment.application.model.ProviderOutcome;
import com.sentinelpay.payment.application.model.ProviderOutcome.Outcome;
import com.sentinelpay.payment.application.port.PaymentProvider;
import com.sentinelpay.payment.application.port.ProviderCounters;
import com.sentinelpay.payment.domain.Provider;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

abstract class StatefulProviderAdapter implements PaymentProvider, ProviderCounters {

    private final ProviderBehavior behavior;
    private final Map<String, String> authStore = new ConcurrentHashMap<>();
    private final Set<String> capturedRefs = ConcurrentHashMap.newKeySet();
    private final AtomicInteger authorizationCount = new AtomicInteger();
    private final AtomicInteger captureCount = new AtomicInteger();

    protected StatefulProviderAdapter(ProviderBehavior behavior) {
        this.behavior = behavior;
    }

    @Override
    public ProviderOutcome authorize(AuthorizeRequest request) {
        long start = System.nanoTime();
        String key = request.downstreamKey();

        String existingRef = authStore.get(key);
        if (existingRef != null) {
            return authorized(existingRef, start);
        }

        ProgrammedOutcome programmed = behavior.nextOutcome(id());
        Outcome outcome = programmed.outcome();

        return switch (outcome) {
            case AUTHORIZED -> {
                String ref = newRef();
                authStore.put(key, ref);
                authorizationCount.incrementAndGet();
                yield authorized(ref, start);
            }
            case AMBIGUOUS_TIMEOUT -> {
                if (programmed.storeOnAmbiguous()) {
                    String ref = newRef();
                    authStore.put(key, ref);
                    authorizationCount.incrementAndGet();
                }
                yield timed(start, Outcome.AMBIGUOUS_TIMEOUT, null);
            }
            case HARD_FAIL -> timed(start, Outcome.HARD_FAIL, null);
            case RETRYABLE -> timed(start, Outcome.RETRYABLE, null);
            default -> throw new IllegalStateException("Unexpected programmed outcome: " + outcome);
        };
    }

    @Override
    public ProviderOutcome reconcile(String downstreamKey) {
        long start = System.nanoTime();
        String ref = authStore.get(downstreamKey);
        if (ref != null) {
            return authorized(ref, start);
        }
        return timed(start, Outcome.NOT_AUTHORIZED, null);
    }

    @Override
    public ProviderOutcome capture(String providerRef) {
        long start = System.nanoTime();
        if (capturedRefs.add(providerRef)) {
            captureCount.incrementAndGet();
        }
        return timed(start, Outcome.CAPTURED, providerRef);
    }

    @Override
    public int authorizationCount() {
        return authorizationCount.get();
    }

    @Override
    public int captureCount() {
        return captureCount.get();
    }

    @Override
    public void resetCounters() {
        authorizationCount.set(0);
        captureCount.set(0);
        authStore.clear();
        capturedRefs.clear();
    }

    protected abstract String newRef();

    private ProviderOutcome authorized(String ref, long startNanos) {
        return timed(startNanos, Outcome.AUTHORIZED, ref);
    }

    private ProviderOutcome timed(long startNanos, Outcome outcome, String ref) {
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
        return new ProviderOutcome(outcome, ref, latencyMs);
    }
}
