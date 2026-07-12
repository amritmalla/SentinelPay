package com.sentinelpay.payment.infrastructure.provider;

import com.sentinelpay.payment.application.model.ProviderBehavior;
import com.sentinelpay.payment.domain.Provider;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
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
