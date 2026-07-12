package com.sentinelpay.payment.application.model;

import com.sentinelpay.payment.application.model.ChargeResult;
import com.sentinelpay.payment.domain.PaymentStatus;
import com.sentinelpay.payment.domain.Provider;

import java.util.UUID;

public record DecisionResult(PaymentDecision decision, ChargeResult terminalResult) {

    public boolean isTerminal() {
        return terminalResult != null;
    }

    public static DecisionResult approve() {
        return new DecisionResult(PaymentDecision.APPROVE, null);
    }

    public static DecisionResult terminal(PaymentStatus status, UUID paymentId) {
        return new DecisionResult(
                status == PaymentStatus.BLOCKED ? PaymentDecision.BLOCK : PaymentDecision.IN_REVIEW,
                new ChargeResult(paymentId, status, null, paymentId));
    }
}
