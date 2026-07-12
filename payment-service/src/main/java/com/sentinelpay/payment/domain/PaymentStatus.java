package com.sentinelpay.payment.domain;

public enum PaymentStatus {
    CREATED,
    RISK_EVALUATED,
    BLOCKED,
    IN_REVIEW,
    AUTHORIZING,
    AUTHORIZED,
    CAPTURED,
    COMPLETED,
    FAILED,
    REFUND_PENDING,
    REFUNDED
}
