package com.sentinelpay.payment.infrastructure.provider;

import com.sentinelpay.payment.application.model.ProviderOutcome;
import com.sentinelpay.payment.application.model.ProviderOutcome.Outcome;
import com.sentinelpay.payment.application.port.PaymentProvider;
import com.sentinelpay.payment.domain.Provider;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MockPayProviderTest {

    private final MockPayProvider provider = new MockPayProvider();

    @Test
    void authorize_returnsAuthorizedWithProviderRef() {
        ProviderOutcome outcome = provider.authorize(new PaymentProvider.AuthorizeRequest(
                UUID.randomUUID(), "key-1", 1000, "USD"));

        assertThat(outcome.outcome()).isEqualTo(Outcome.AUTHORIZED);
        assertThat(outcome.providerRef()).startsWith("mockpay_");
    }

    @Test
    void capture_returnsCaptured() {
        ProviderOutcome outcome = provider.capture("mockpay_ref");

        assertThat(outcome.outcome()).isEqualTo(Outcome.CAPTURED);
        assertThat(outcome.providerRef()).isEqualTo("mockpay_ref");
    }

    @Test
    void id_isMockPay() {
        assertThat(provider.id()).isEqualTo(Provider.MOCKPAY);
    }
}
