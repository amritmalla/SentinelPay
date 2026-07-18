package com.sentinelpay.payment.application.port;

import com.sentinelpay.payment.domain.Provider;

/**
 * Thompson-sampling posteriors (Beta-Bernoulli) per provider, stored in Redis.
 */
public interface ProviderBanditStore {

    Posterior read(Provider provider);

    void recordOutcome(Provider provider, boolean success);

    record Posterior(double alpha, double beta, boolean degraded) {
        public static Posterior prior() {
            return new Posterior(1.0, 1.0, false);
        }

        public static Posterior degradedPrior() {
            return new Posterior(1.0, 1.0, true);
        }

        public double mean() {
            return alpha / (alpha + beta);
        }
    }
}
