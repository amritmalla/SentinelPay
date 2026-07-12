package com.sentinelpay.payment.application;

import com.sentinelpay.payment.application.port.RiskEvaluator;

import java.util.List;

public final class RiskFallback {

    private RiskFallback() {
    }

    public static RiskEvaluator.RiskDecision forAmount(long amountCents) {
        String rec = amountCents <= 50_000 ? "APPROVE" : "REVIEW";
        return new RiskEvaluator.RiskDecision(0.5, rec, List.of("fallback:amount"), "fallback-v0", true);
    }
}
