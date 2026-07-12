package com.sentinelpay.payment.application.port;

import com.sentinelpay.payment.application.model.ProviderOutcome;
import com.sentinelpay.payment.domain.Provider;

import java.util.UUID;

public interface PaymentProvider {

    Provider id();

    ProviderOutcome authorize(AuthorizeRequest request);

    ProviderOutcome capture(String providerRef);

    record AuthorizeRequest(
            UUID paymentId,
            String idempotencyKey,
            long amountCents,
            String currency) {
    }
}
