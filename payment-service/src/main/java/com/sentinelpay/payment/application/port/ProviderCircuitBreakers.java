package com.sentinelpay.payment.application.port;

import com.sentinelpay.payment.domain.Provider;

/**
 * Per-provider circuit breakers driven from charge attempt outcomes (not AOP-wrapped calls).
 */
public interface ProviderCircuitBreakers {

    /** CLOSED, OPEN, HALF_OPEN, or DISABLED when breakers are off. */
    String stateName(Provider provider);

    void recordOutcome(Provider provider, boolean success, long elapsedMs);
}
