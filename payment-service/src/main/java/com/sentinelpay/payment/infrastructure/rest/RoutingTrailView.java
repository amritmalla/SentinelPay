package com.sentinelpay.payment.infrastructure.rest;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sentinelpay.payment.application.routing.ProviderRoutingRationale;

import java.util.List;
import java.util.Map;

public record RoutingTrailView(
        @JsonProperty("policy") String policy,
        @JsonProperty("ordered_providers") List<String> orderedProviders,
        @JsonProperty("flags") List<String> flags,
        @JsonProperty("providers") Map<String, ProviderRoutingRationale> providers) {
}
