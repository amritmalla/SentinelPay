package com.sentinelpay.payment.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public final class PaymentStateMachine {

    private static final Map<PaymentStatus, Set<PaymentStatus>> ALLOWED = new EnumMap<>(PaymentStatus.class);

    static {
        allow(PaymentStatus.CREATED, PaymentStatus.RISK_EVALUATED, PaymentStatus.FAILED);
        allow(PaymentStatus.RISK_EVALUATED, PaymentStatus.BLOCKED, PaymentStatus.IN_REVIEW, PaymentStatus.AUTHORIZING);
        allow(PaymentStatus.AUTHORIZING, PaymentStatus.AUTHORIZED, PaymentStatus.FAILED);
        allow(PaymentStatus.AUTHORIZED, PaymentStatus.CAPTURED, PaymentStatus.FAILED);
        allow(PaymentStatus.CAPTURED, PaymentStatus.COMPLETED);
        allow(PaymentStatus.COMPLETED, PaymentStatus.REFUND_PENDING);
        allow(PaymentStatus.REFUND_PENDING, PaymentStatus.REFUNDED, PaymentStatus.FAILED);
    }

    private PaymentStateMachine() {
    }

    public static void assertTransition(PaymentStatus from, PaymentStatus to) {
        Set<PaymentStatus> targets = ALLOWED.get(from);
        if (targets == null || !targets.contains(to)) {
            throw new IllegalStateException("Invalid payment transition: " + from + " -> " + to);
        }
    }

    public static boolean isTerminal(PaymentStatus status) {
        return EnumSet.of(
                PaymentStatus.BLOCKED,
                PaymentStatus.IN_REVIEW,
                PaymentStatus.FAILED,
                PaymentStatus.REFUNDED).contains(status);
    }

    private static void allow(PaymentStatus from, PaymentStatus... to) {
        EnumSet<PaymentStatus> targets = EnumSet.noneOf(PaymentStatus.class);
        for (PaymentStatus status : to) {
            targets.add(status);
        }
        ALLOWED.put(from, targets);
    }
}
