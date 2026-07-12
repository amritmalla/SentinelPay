package com.sentinelpay.payment.application.model;

public record ProviderOutcome(Outcome outcome, String providerRef, long latencyMs) {

    public enum Outcome {
        AUTHORIZED,
        CAPTURED,
        HARD_FAIL,
        RETRYABLE,
        AMBIGUOUS_TIMEOUT,
        NOT_AUTHORIZED,
        REFUNDED
    }
}
