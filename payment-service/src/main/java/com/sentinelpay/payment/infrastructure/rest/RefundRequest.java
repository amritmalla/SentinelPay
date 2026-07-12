package com.sentinelpay.payment.infrastructure.rest;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Positive;

public record RefundRequest(
        @JsonProperty("amount_cents") @Positive long amountCents,
        String reason) {
}
