package com.sentinelpay.payment.infrastructure.rest;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

public record RefundResponse(
        @JsonProperty("refund_id") UUID refundId,
        @JsonProperty("payment_id") UUID paymentId,
        @JsonProperty("amount_cents") long amountCents,
        String status,
        String reason,
        @JsonProperty("created_at") Instant createdAt) {

    static RefundResponse from(com.sentinelpay.payment.application.model.RefundResult result) {
        return new RefundResponse(
                result.refundId(),
                result.paymentId(),
                result.amountCents(),
                result.status(),
                result.reason(),
                result.createdAt());
    }
}
