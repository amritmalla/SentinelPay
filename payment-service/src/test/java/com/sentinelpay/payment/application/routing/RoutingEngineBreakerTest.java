package com.sentinelpay.payment.application.routing;

import com.sentinelpay.payment.application.port.ProviderCircuitBreakers;
import com.sentinelpay.payment.application.port.ProviderHealthStore;
import com.sentinelpay.payment.config.MerchantCountryProperties;
import com.sentinelpay.payment.config.RoutingProperties;
import com.sentinelpay.payment.domain.Provider;
import com.sentinelpay.payment.infrastructure.metrics.RoutingMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoutingEngineBreakerTest {

    private RoutingProperties properties;
    private MerchantCountryProperties merchantProperties;
    private ProviderHealthStore healthStore;
    private ProviderCircuitBreakers circuitBreakers;
    private RoutingEngine engine;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        properties = new RoutingProperties();
        properties.getBreaker().setEnabled(true);
        merchantProperties = new MerchantCountryProperties();
        healthStore = mock(ProviderHealthStore.class);
        circuitBreakers = mock(ProviderCircuitBreakers.class);
        meterRegistry = new SimpleMeterRegistry();
        when(healthStore.read(any())).thenReturn(ProviderHealthStore.ProviderHealthView.neutral());
        engine = new RoutingEngine(
                properties,
                merchantProperties,
                healthStore,
                circuitBreakers,
                List.of(new StaticRankingPolicy(), new ScoredRankingPolicy()),
                new RoutingMetrics(meterRegistry));
    }

    @Test
    void openBreaker_removesProviderFromOrder() {
        when(circuitBreakers.stateName(Provider.MOCKPAY)).thenReturn("OPEN");
        when(circuitBreakers.stateName(Provider.STRIPE)).thenReturn("CLOSED");

        RoutingDecision decision = engine.decide(sampleContext());

        assertThat(decision.orderedProviders()).containsExactly(Provider.STRIPE);
        assertThat(decision.rationaleByProvider().get(Provider.MOCKPAY).breakerState()).isEqualTo("OPEN");
        assertThat(decision.rationaleByProvider().get(Provider.MOCKPAY).eligible()).isFalse();
        assertThat(decision.flags()).isEmpty();
    }

    @Test
    void allBreakersOpen_bypassesFilteringAndSetsFlag() {
        when(circuitBreakers.stateName(Provider.MOCKPAY)).thenReturn("OPEN");
        when(circuitBreakers.stateName(Provider.STRIPE)).thenReturn("OPEN");

        RoutingDecision decision = engine.decide(sampleContext());

        assertThat(decision.orderedProviders()).containsExactly(Provider.MOCKPAY, Provider.STRIPE);
        assertThat(decision.flags()).contains(RoutingDecision.FLAG_BREAKERS_BYPASSED);
        assertThat(meterRegistry.get("sentinelpay_routing_breaker_bypass_total").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void halfOpenProvider_ranksLastAmongAdmitted() {
        when(circuitBreakers.stateName(Provider.MOCKPAY)).thenReturn("HALF_OPEN");
        when(circuitBreakers.stateName(Provider.STRIPE)).thenReturn("CLOSED");

        RoutingDecision decision = engine.decide(sampleContext());

        assertThat(decision.orderedProviders()).containsExactly(Provider.STRIPE, Provider.MOCKPAY);
        assertThat(decision.rationaleByProvider().get(Provider.MOCKPAY).breakerState()).isEqualTo("HALF_OPEN");
    }

    private static RoutingContext sampleContext() {
        return new RoutingContext(UUID.randomUUID(), UUID.randomUUID(), 2_500, "USD");
    }
}
