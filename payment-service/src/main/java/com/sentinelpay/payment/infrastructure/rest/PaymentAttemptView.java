package com.sentinelpay.payment.infrastructure.rest;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PaymentAttemptView(
        @JsonProperty("attempt_number") short attemptNumber,
        String provider,
        String outcome,
        @JsonProperty("provider_ref") String providerRef,
        @JsonProperty("latency_ms") Integer latencyMs) {
}
