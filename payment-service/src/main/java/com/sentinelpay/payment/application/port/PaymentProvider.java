package com.sentinelpay.payment.application.port;

import com.sentinelpay.payment.application.model.ProviderOutcome;
import com.sentinelpay.payment.domain.Provider;

import java.util.UUID;

public interface PaymentProvider {

    Provider id();

    ProviderOutcome authorize(AuthorizeRequest request);

    ProviderOutcome reconcile(String downstreamKey);

    ProviderOutcome capture(String providerRef);

    ProviderOutcome refund(String providerRef, long amountCents);

    record AuthorizeRequest(UUID paymentId, String downstreamKey, long amountCents, String currency) {
    }
}
