package com.sentinelpay.payment.infrastructure.rest;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sentinelpay.payment.domain.PaymentStatus;
import com.sentinelpay.payment.infrastructure.risk.RiskTrailView;

import java.util.List;
import java.util.UUID;

public record DecisionTrail(
        @JsonProperty("payment_id") UUID paymentId,
        PaymentStatus status,
        RiskTrailView risk,
        List<PaymentAttemptView> attempts) {
}
