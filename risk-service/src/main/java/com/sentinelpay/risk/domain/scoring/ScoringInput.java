package com.sentinelpay.risk.domain.scoring;

import java.util.UUID;

public record ScoringInput(
        UUID transactionId,
        UUID merchantId,
        long amountCents,
        String currency,
        String customerEmail,
        long velocityLastHour,
        String merchantCategory,
        String cardCountry,
        String merchantCountry,
        Long timestampEpochMs) {

    /** Compatibility constructor used by rule-unit tests that omit ML feature fields. */
    public ScoringInput(
            UUID transactionId,
            UUID merchantId,
            long amountCents,
            String currency,
            String customerEmail,
            long velocityLastHour) {
        this(transactionId, merchantId, amountCents, currency, customerEmail, velocityLastHour,
                null, null, null, null);
    }

    public ScoringInput withVelocity(long velocity) {
        return new ScoringInput(
                transactionId,
                merchantId,
                amountCents,
                currency,
                customerEmail,
                velocity,
                merchantCategory,
                cardCountry,
                merchantCountry,
                timestampEpochMs);
    }
}
