package com.sentinelpay.payment.application.model;

import com.sentinelpay.payment.domain.PaymentStatus;
import com.sentinelpay.payment.domain.Provider;

import java.util.UUID;

public record ChargeCommand(
        UUID merchantId,
        long amountCents,
        String currency,
        String customerEmail,
        String idempotencyKey,
        String correlationId) {
}
