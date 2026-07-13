package com.sentinelpay.payment.infrastructure.rest;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public record ChargeRequest(
        @JsonProperty("amount_cents") @Positive long amountCents,
        @Pattern(regexp = "^[A-Z]{3}$") String currency,
        @JsonProperty("customer_email") @Email String customerEmail) {
}
