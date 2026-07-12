package com.sentinelpay.payment.application.model;

import java.util.UUID;

public sealed interface BeginRefundOutcome {

    record Stored(RefundResult result) implements BeginRefundOutcome {
    }

    record Started(RefundContext context) implements BeginRefundOutcome {
    }

    record RefundContext(
            UUID refundId,
            UUID paymentId,
            UUID merchantId,
            long amountCents,
            String reason,
            String providerRef,
            String provider,
            String idempotencyKey,
            String correlationId) {
    }
}
