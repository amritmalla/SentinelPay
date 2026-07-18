package com.sentinelpay.payment.infrastructure.rest.ops;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

public record OpsRoutingProvidersResponse(
        boolean degraded,
        List<OpsProviderView> providers) {

    public record OpsProviderView(
            String provider,
            boolean enabled,
            @JsonProperty("breaker_state") String breakerState,
            OpsHealthView health,
            OpsBanditView bandit,
            OpsFeeView fee) {
    }

    public record OpsHealthView(
            @JsonProperty("success_rate") double successRate,
            @JsonProperty("latency_ewma_ms") long latencyEwmaMs,
            boolean degraded) {
    }

    public record OpsBanditView(
            double alpha,
            double beta,
            double mean,
            boolean degraded) {
    }

    public record OpsFeeView(
            @JsonProperty("fee_bps") int feeBps,
            @JsonProperty("fee_fixed_cents") int feeFixedCents) {
    }
}
