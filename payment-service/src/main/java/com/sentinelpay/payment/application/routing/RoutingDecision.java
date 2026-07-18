package com.sentinelpay.payment.application.routing;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.sentinelpay.payment.domain.Provider;

import java.util.List;
import java.util.Map;

/**
 * Immutable output of the routing pipeline: ordered providers plus per-provider rationale.
 */
public record RoutingDecision(
        String policy,
        List<Provider> orderedProviders,
        Map<Provider, ProviderRoutingRationale> rationaleByProvider,
        List<String> flags,
        SplitAssignment split) {

    public static final String FLAG_FALLBACK_STATIC = "FALLBACK_STATIC";
    public static final String FLAG_BREAKERS_BYPASSED = "BREAKERS_BYPASSED";
    public static final String FLAG_SPLIT_ASSIGNED = "SPLIT_ASSIGNED";

    public RoutingDecision(
            String policy,
            List<Provider> orderedProviders,
            Map<Provider, ProviderRoutingRationale> rationaleByProvider,
            List<String> flags) {
        this(policy, orderedProviders, rationaleByProvider, flags, null);
    }

    public Provider firstChoice() {
        return orderedProviders.isEmpty() ? null : orderedProviders.get(0);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SplitAssignment(
            @JsonProperty("bucket") int bucket,
            @JsonProperty("assigned_provider") String assignedProvider) {
    }
}
