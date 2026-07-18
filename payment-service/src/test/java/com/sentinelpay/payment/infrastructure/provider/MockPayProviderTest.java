package com.sentinelpay.payment.infrastructure.provider;

import com.sentinelpay.payment.application.model.ProviderBehavior;
import com.sentinelpay.payment.application.model.ProviderBehavior.ProgrammedOutcome;
import com.sentinelpay.payment.application.model.ProviderOutcome;
import com.sentinelpay.payment.application.model.ProviderOutcome.Outcome;
import com.sentinelpay.payment.application.port.PaymentProvider;
import com.sentinelpay.payment.domain.Provider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MockPayProviderTest {

    private final ProviderBehavior behavior = new ProviderBehavior();
    private final MockPayProvider provider = new MockPayProvider(behavior);

    @BeforeEach
    void reset() {
        behavior.reset();
        provider.resetCounters();
    }

    @Test
    void authorize_returnsAuthorizedWithProviderRef() {
        ProviderOutcome outcome = provider.authorize(request("key-1"));

        assertThat(outcome.outcome()).isEqualTo(Outcome.AUTHORIZED);
        assertThat(outcome.providerRef()).startsWith("mockpay_");
    }

    @Test
    void authorize_sameDownstreamKey_isIdempotent() {
        ProviderOutcome first = provider.authorize(request("key-dup"));
        ProviderOutcome second = provider.authorize(request("key-dup"));

        assertThat(second.outcome()).isEqualTo(Outcome.AUTHORIZED);
        assertThat(second.providerRef()).isEqualTo(first.providerRef());
        assertThat(provider.authorizationCount()).isEqualTo(1);
    }

    @Test
    void ambiguousTimeout_storesAuth_reconcileReturnsAuthorized() {
        behavior.program(Provider.MOCKPAY, Outcome.AMBIGUOUS_TIMEOUT);

        ProviderOutcome authorize = provider.authorize(request("key-ambig"));
        assertThat(authorize.outcome()).isEqualTo(Outcome.AMBIGUOUS_TIMEOUT);

        ProviderOutcome reconciled = provider.reconcile(reconcileRequest("key-ambig"));
        assertThat(reconciled.outcome()).isEqualTo(Outcome.AUTHORIZED);
        assertThat(reconciled.providerRef()).isNotNull();
        assertThat(provider.authorizationCount()).isEqualTo(1);
    }

    @Test
    void hardFail_doesNotStore_reconcileReturnsNotAuthorized() {
        behavior.program(Provider.MOCKPAY, Outcome.HARD_FAIL);

        ProviderOutcome authorize = provider.authorize(request("key-fail"));
        assertThat(authorize.outcome()).isEqualTo(Outcome.HARD_FAIL);

        ProviderOutcome reconciled = provider.reconcile(reconcileRequest("key-fail"));
        assertThat(reconciled.outcome()).isEqualTo(Outcome.NOT_AUTHORIZED);
        assertThat(provider.authorizationCount()).isZero();
    }

    @Test
    void ambiguousWithoutStore_reconcileReturnsNotAuthorized() {
        behavior.program(Provider.MOCKPAY, ProgrammedOutcome.ambiguousWithoutStore());

        provider.authorize(request("key-no-store"));
        ProviderOutcome reconciled = provider.reconcile(reconcileRequest("key-no-store"));

        assertThat(reconciled.outcome()).isEqualTo(Outcome.NOT_AUTHORIZED);
        assertThat(provider.authorizationCount()).isZero();
    }

    @Test
    void capture_returnsCapturedAndIncrementsCount() {
        ProviderOutcome outcome = provider.capture("mockpay_ref");

        assertThat(outcome.outcome()).isEqualTo(Outcome.CAPTURED);
        assertThat(outcome.providerRef()).isEqualTo("mockpay_ref");
        assertThat(provider.captureCount()).isEqualTo(1);
    }

    @Test
    void capture_sameProviderRef_isIdempotent() {
        ProviderOutcome first = provider.capture("mockpay_ref");
        ProviderOutcome second = provider.capture("mockpay_ref");

        assertThat(second.outcome()).isEqualTo(Outcome.CAPTURED);
        assertThat(second.providerRef()).isEqualTo(first.providerRef());
        assertThat(provider.captureCount()).isEqualTo(1);
    }

    @Test
    void id_isMockPay() {
        assertThat(provider.id()).isEqualTo(Provider.MOCKPAY);
    }

    private static PaymentProvider.AuthorizeRequest request(String key) {
        return new PaymentProvider.AuthorizeRequest(UUID.randomUUID(), key, 1000, "USD");
    }

    private static PaymentProvider.ReconcileRequest reconcileRequest(String key) {
        return new PaymentProvider.ReconcileRequest(UUID.randomUUID(), key, 1000, "USD");
    }
}
