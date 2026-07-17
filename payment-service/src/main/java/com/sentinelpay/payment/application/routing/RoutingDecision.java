package com.sentinelpay.payment.application.routing;

import com.sentinelpay.payment.domain.Provider;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable output of the routing pipeline: ordered providers plus per-provider rationale.
 */
public record RoutingDecision(
        String policy,
        List<Provider> orderedProviders,
        Map<Provider, ProviderRoutingRationale> rationaleByProvider,
        List<String> flags) {

    public static final String FLAG_FALLBACK_STATIC = "FALLBACK_STATIC";
    public static final String FLAG_BREAKERS_BYPASSED = "BREAKERS_BYPASSED";

    public Provider firstChoice() {
        return orderedProviders.isEmpty() ? null : orderedProviders.get(0);
    }
}
