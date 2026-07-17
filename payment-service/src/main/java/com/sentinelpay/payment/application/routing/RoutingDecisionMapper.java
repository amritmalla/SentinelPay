package com.sentinelpay.payment.application.routing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelpay.payment.domain.Provider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RoutingDecisionMapper {

    private RoutingDecisionMapper() {
    }

    public static RoutingDecisionDocument toDocument(RoutingDecision decision) {
        Map<String, ProviderRoutingRationale> rationale = new LinkedHashMap<>();
        decision.rationaleByProvider().forEach((provider, value) -> rationale.put(provider.dbValue(), value));
        List<String> ordered = decision.orderedProviders().stream().map(Provider::dbValue).toList();
        return new RoutingDecisionDocument(decision.policy(), ordered, decision.flags(), rationale);
    }

    public static String toJson(RoutingDecision decision, ObjectMapper objectMapper) {
        try {
            return objectMapper.writeValueAsString(toDocument(decision));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize routing decision", ex);
        }
    }

    public static RoutingDecisionDocument fromJson(String json, ObjectMapper objectMapper) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, RoutingDecisionDocument.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to deserialize routing decision", ex);
        }
    }

    public record RoutingDecisionDocument(
            String policy,
            List<String> orderedProviders,
            List<String> flags,
            Map<String, ProviderRoutingRationale> rationaleByProvider) {
    }
}
