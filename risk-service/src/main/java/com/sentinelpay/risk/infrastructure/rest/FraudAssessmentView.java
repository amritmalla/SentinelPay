package com.sentinelpay.risk.infrastructure.rest;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record FraudAssessmentView(
        @JsonProperty("transaction_id") UUID transactionId,
        @JsonProperty("merchant_id") UUID merchantId,
        BigDecimal score,
        String recommendation,
        @JsonProperty("contributing_factors") List<String> contributingFactors,
        @JsonProperty("model_version") String modelVersion,
        @JsonProperty("fallback_used") boolean fallbackUsed,
        @JsonProperty("created_at") Instant createdAt) {
}
