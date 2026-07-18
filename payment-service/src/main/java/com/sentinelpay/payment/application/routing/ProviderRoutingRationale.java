package com.sentinelpay.payment.application.routing;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProviderRoutingRationale(
        @JsonProperty("eligible") boolean eligible,
        @JsonProperty("matched_rules") List<String> matchedRules,
        @JsonProperty("breaker_state") String breakerState,
        @JsonProperty("success_rate") Double successRate,
        @JsonProperty("latency_ewma_ms") Long latencyEwmaMs,
        @JsonProperty("score") Double score,
        @JsonProperty("score_components") ScoreComponents scoreComponents,
        @JsonProperty("rank") Integer rank,
        @JsonProperty("bandit") BanditRationale bandit) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ScoreComponents(
            @JsonProperty("success_component") double successComponent,
            @JsonProperty("latency_component") double latencyComponent,
            @JsonProperty("cost_penalty") Double costPenalty) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BanditRationale(
            @JsonProperty("alpha") double alpha,
            @JsonProperty("beta") double beta,
            @JsonProperty("posterior_mean") double posteriorMean,
            @JsonProperty("sampled_theta") double sampledTheta,
            @JsonProperty("cost_penalty") double costPenalty) {
    }

    public static ProviderRoutingRationale ineligible(List<String> rules) {
        return new ProviderRoutingRationale(false, rules, null, null, null, null, null, null, null);
    }

    public static ProviderRoutingRationale ranked(
            List<String> rules,
            double successRate,
            long latencyEwmaMs,
            double score,
            ScoreComponents components,
            int rank) {
        return ranked(rules, null, successRate, latencyEwmaMs, score, components, rank);
    }

    public static ProviderRoutingRationale ranked(
            List<String> rules,
            String breakerState,
            double successRate,
            long latencyEwmaMs,
            double score,
            ScoreComponents components,
            int rank) {
        return new ProviderRoutingRationale(
                true, rules, breakerState, successRate, latencyEwmaMs, score, components, rank, null);
    }

    public static ProviderRoutingRationale banditRanked(BanditRationale bandit, int rank) {
        return new ProviderRoutingRationale(
                true,
                List.of(),
                null,
                bandit.posteriorMean(),
                null,
                bandit.sampledTheta(),
                null,
                rank,
                bandit);
    }

    public static ProviderRoutingRationale breakerExcluded(String breakerState) {
        return new ProviderRoutingRationale(false, List.of(), breakerState, null, null, null, null, null, null);
    }

    public ProviderRoutingRationale withBreakerState(String breakerState) {
        return new ProviderRoutingRationale(
                eligible, matchedRules, breakerState, successRate, latencyEwmaMs, score, scoreComponents, rank, bandit);
    }
}
