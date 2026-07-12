package com.sentinelpay.payment.infrastructure.rest;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sentinelpay.payment.domain.PaymentStatus;
import com.sentinelpay.payment.domain.Provider;

import java.util.UUID;

public record ChargeResponse(
        @JsonProperty("payment_id") UUID paymentId,
        PaymentStatus status,
        Provider provider,
        @JsonProperty("trail_id") UUID trailId) {
}
