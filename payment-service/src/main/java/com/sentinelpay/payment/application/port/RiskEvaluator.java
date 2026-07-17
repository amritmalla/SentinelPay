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
            String customerEmail,
            String merchantCategory,
            String cardCountry,
            String merchantCountry) {

        public RiskInput(
                UUID transactionId,
                UUID merchantId,
                long amountCents,
                String currency,
                String customerEmail) {
            this(transactionId, merchantId, amountCents, currency, customerEmail, null, null, null);
        }
    }

    record RiskDecision(
            double score,
            String recommendation,
            List<String> factors,
            String modelVersion,
            boolean fallbackUsed) {
    }
}
