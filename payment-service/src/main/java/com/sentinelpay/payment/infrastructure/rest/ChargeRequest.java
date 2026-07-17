package com.sentinelpay.payment.infrastructure.rest;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record ChargeRequest(
        @JsonProperty("amount_cents") @Positive long amountCents,
        @Pattern(regexp = "^[A-Z]{3}$") String currency,
        @JsonProperty("customer_email") @Email String customerEmail,
        @JsonProperty("merchant_category") @Size(max = 64) String merchantCategory,
        @JsonProperty("card_country") @Pattern(regexp = "^[A-Z]{2}$") String cardCountry) {
}
