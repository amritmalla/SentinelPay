package com.sentinelpay.payment.infrastructure.rest;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sentinelpay.payment.domain.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentView(
        @JsonProperty("payment_id") UUID paymentId,
        @JsonProperty("merchant_id") UUID merchantId,
        PaymentStatus status,
        @JsonProperty("amount_cents") long amountCents,
        String currency,
        String provider,
        RiskSummaryView risk,
        @JsonProperty("trail_id") UUID trailId,
        @JsonProperty("failure_reason") String failureReason,
        @JsonProperty("created_at") Instant createdAt) {

    public record RiskSummaryView(
            BigDecimal score,
            String recommendation,
            @JsonProperty("model_version") String modelVersion,
            @JsonProperty("fallback_used") Boolean fallbackUsed) {
    }
}
