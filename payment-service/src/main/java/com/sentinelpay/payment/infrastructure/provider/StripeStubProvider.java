package com.sentinelpay.payment.infrastructure.provider;

import com.sentinelpay.payment.application.model.ProviderBehavior;
import com.sentinelpay.payment.domain.Provider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@ConditionalOnProperty(name = "sentinelpay.providers.stripe.mode", havingValue = "stub", matchIfMissing = true)
public class StripeStubProvider extends StatefulProviderAdapter {

    public StripeStubProvider(ProviderBehavior behavior) {
        super(behavior);
    }

    @Override
    public Provider id() {
        return Provider.STRIPE;
    }

    @Override
    protected String newRef() {
        return "stripe_" + UUID.randomUUID();
    }
}
