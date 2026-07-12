package com.sentinelpay.payment.application.model;

import com.sentinelpay.payment.domain.PaymentStatus;
import com.sentinelpay.payment.domain.Provider;

import java.util.UUID;

public record ChargeResult(
        UUID paymentId,
        PaymentStatus status,
        Provider provider,
        UUID trailId) {
}
