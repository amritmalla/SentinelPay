package com.sentinelpay.payment.application.model;

import java.util.UUID;

public record RefundCommand(
        UUID paymentId,
        long amountCents,
        String reason,
        String idempotencyKey,
        String correlationId) {
}
