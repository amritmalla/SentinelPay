package com.sentinelpay.payment.infrastructure.rest.ops;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sentinelpay.payment.config.RoutingProperties;

import java.util.Map;

public record OpsRoutingConfigResponse(
        boolean enabled,
        String policy,
        Map<String, Integer> split,
        @JsonProperty("health_window_minutes") int healthWindowMinutes,
        @JsonProperty("latency_ceiling_ms") long latencyCeilingMs,
        @JsonProperty("latency_ewma_alpha") double latencyEwmaAlpha,
        OpsScoredConfigView scored,
        OpsBanditConfigView bandit,
        OpsBreakerConfigView breaker) {

    public record OpsScoredConfigView(
            @JsonProperty("weight_success") double weightSuccess,
            @JsonProperty("weight_latency") double weightLatency,
            @JsonProperty("cost_tiebreak_epsilon") double costTiebreakEpsilon) {
    }

    public record OpsBanditConfigView(
            @JsonProperty("decay_half_life_minutes") int decayHalfLifeMinutes,
            @JsonProperty("cost_weight") double costWeight) {
    }

    public record OpsBreakerConfigView(
            boolean enabled,
            @JsonProperty("sliding_window_size") int slidingWindowSize,
            @JsonProperty("failure_rate_threshold") int failureRateThreshold,
            @JsonProperty("wait_duration_open_ms") long waitDurationOpenMs,
            @JsonProperty("permitted_calls_half_open") int permittedCallsHalfOpen,
            @JsonProperty("minimum_number_of_calls") int minimumNumberOfCalls) {
    }

    public static OpsRoutingConfigResponse from(RoutingProperties properties) {
        RoutingProperties.Scored scored = properties.getScored();
        RoutingProperties.Bandit bandit = properties.getBandit();
        RoutingProperties.Breaker breaker = properties.getBreaker();
        return new OpsRoutingConfigResponse(
                properties.isEnabled(),
                properties.getPolicy(),
                properties.getSplit(),
                properties.getHealthWindowMinutes(),
                properties.getLatencyCeilingMs(),
                properties.getLatencyEwmaAlpha(),
                new OpsScoredConfigView(
                        scored.getWeightSuccess(),
                        scored.getWeightLatency(),
                        scored.getCostTiebreakEpsilon()),
                new OpsBanditConfigView(bandit.getDecayHalfLifeMinutes(), bandit.getCostWeight()),
                new OpsBreakerConfigView(
                        breaker.isEnabled(),
                        breaker.getSlidingWindowSize(),
                        breaker.getFailureRateThreshold(),
                        breaker.getWaitDurationOpenMs(),
                        breaker.getPermittedCallsHalfOpen(),
                        breaker.getMinimumNumberOfCalls()));
    }
}
