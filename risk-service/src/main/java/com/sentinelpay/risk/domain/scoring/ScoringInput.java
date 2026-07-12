package com.sentinelpay.risk.domain.scoring;

import java.util.UUID;

public record ScoringInput(
        UUID transactionId,
        UUID merchantId,
        long amountCents,
        String currency,
        String customerEmail,
        long velocityLastHour) {

    public ScoringInput withVelocity(long velocity) {
        return new ScoringInput(
                transactionId, merchantId, amountCents, currency, customerEmail, velocity);
    }
}
