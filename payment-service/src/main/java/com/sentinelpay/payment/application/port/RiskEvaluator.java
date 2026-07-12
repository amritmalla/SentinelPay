package com.sentinelpay.payment.application.port;

import java.util.List;
import java.util.UUID;

public interface RiskEvaluator {

    RiskDecision evaluate(RiskInput input);

    record RiskInput(
            UUID transactionId,
            UUID merchantId,
            long amountCents,
            String currency,
            String customerEmail) {
    }

    record RiskDecision(
            double score,
            String recommendation,
            List<String> factors,
            String modelVersion,
            boolean fallbackUsed) {
    }
}
