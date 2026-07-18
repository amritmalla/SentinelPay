package com.sentinelpay.payment.infrastructure.rest;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sentinelpay.payment.domain.PaymentStatus;

import java.time.Instant;
import java.util.UUID;

public record PaymentSummaryView(
        @JsonProperty("payment_id") UUID paymentId,
        PaymentStatus status,
        @JsonProperty("amount_cents") long amountCents,
        String currency,
        @JsonProperty("created_at") Instant createdAt,
        MerchantRiskView risk,
        OutcomeView outcome) {

    public record MerchantRiskView(
            String recommendation,
            @JsonProperty("score_band") String scoreBand) {
    }

    public record OutcomeView(
            @JsonProperty("provider_count") int providerCount,
            boolean recovered,
            @JsonProperty("final_provider_slot") String finalProviderSlot) {
    }
}
