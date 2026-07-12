package com.sentinelpay.payment.infrastructure.provider;

import com.sentinelpay.payment.application.model.ProviderOutcome;
import com.sentinelpay.payment.application.model.ProviderOutcome.Outcome;
import com.sentinelpay.payment.application.port.PaymentProvider;
import com.sentinelpay.payment.domain.Provider;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class MockPayProvider implements PaymentProvider {

    @Override
    public Provider id() {
        return Provider.MOCKPAY;
    }

    @Override
    public ProviderOutcome authorize(AuthorizeRequest request) {
        long start = System.nanoTime();
        String providerRef = "mockpay_" + UUID.randomUUID();
        long latencyMs = (System.nanoTime() - start) / 1_000_000;
        return new ProviderOutcome(Outcome.AUTHORIZED, providerRef, latencyMs);
    }

    @Override
    public ProviderOutcome capture(String providerRef) {
        long start = System.nanoTime();
        long latencyMs = (System.nanoTime() - start) / 1_000_000;
        return new ProviderOutcome(Outcome.CAPTURED, providerRef, latencyMs);
    }
}
