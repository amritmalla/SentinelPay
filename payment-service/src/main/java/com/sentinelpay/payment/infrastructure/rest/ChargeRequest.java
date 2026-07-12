package com.sentinelpay.payment.infrastructure.rest;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

public record ChargeRequest(
        @JsonProperty("merchant_id") UUID merchantId,
        @JsonProperty("amount_cents") @Positive long amountCents,
        @Pattern(regexp = "^[A-Z]{3}$") String currency,
        @JsonProperty("customer_email") @Email String customerEmail) {
}
