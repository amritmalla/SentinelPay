package com.sentinelpay.payment.application.model;

import java.util.UUID;

public record ChargeCommand(
        UUID merchantId,
        long amountCents,
        String currency,
        String customerEmail,
        String idempotencyKey,
        String correlationId,
        String merchantCategory,
        String cardCountry,
        String merchantCountry) {

    /** Compatibility constructor for reconcile/webhook paths that omit risk feature fields. */
    public ChargeCommand(
            UUID merchantId,
            long amountCents,
            String currency,
            String customerEmail,
            String idempotencyKey,
            String correlationId) {
        this(merchantId, amountCents, currency, customerEmail, idempotencyKey, correlationId,
                null, null, null);
    }
}
