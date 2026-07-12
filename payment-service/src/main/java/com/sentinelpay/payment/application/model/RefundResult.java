package com.sentinelpay.payment.application.model;

import com.sentinelpay.payment.domain.PaymentStatus;

import java.time.Instant;
import java.util.UUID;

public record RefundResult(
        UUID refundId,
        UUID paymentId,
        long amountCents,
        String status,
        String reason,
        PaymentStatus paymentStatus,
        Instant createdAt) {
}
