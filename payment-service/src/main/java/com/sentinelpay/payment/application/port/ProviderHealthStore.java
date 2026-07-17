package com.sentinelpay.payment.application.port;

import com.sentinelpay.payment.domain.Provider;

/**
 * Charge-path view of provider health (sliding success window + latency EWMA).
 * Fail-open: reads return {@link ProviderHealthView#neutral()} on Redis errors.
 */
public interface ProviderHealthStore {

    void recordOutcome(Provider provider, boolean success, long latencyMs);

    ProviderHealthView read(Provider provider);

    record ProviderHealthView(double successRate, long latencyEwmaMs, boolean degraded) {
        public static ProviderHealthView neutral() {
            return new ProviderHealthView(0.5, 0L, false);
        }

        public static ProviderHealthView degradedNeutral() {
            return new ProviderHealthView(0.5, 0L, true);
        }
    }
}
