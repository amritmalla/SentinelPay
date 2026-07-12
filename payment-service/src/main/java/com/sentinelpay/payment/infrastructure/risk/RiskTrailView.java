package com.sentinelpay.payment.infrastructure.risk;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record RiskTrailView(
        @JsonProperty("transaction_id") UUID transactionId,
        BigDecimal score,
        String recommendation,
        @JsonProperty("contributing_factors") List<String> contributingFactors,
        @JsonProperty("model_version") String modelVersion,
        @JsonProperty("fallback_used") Boolean fallbackUsed) {
}
