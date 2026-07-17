package com.sentinelpay.payment.application.routing;

import com.sentinelpay.payment.application.port.ProviderCircuitBreakers;
import com.sentinelpay.payment.application.port.ProviderHealthStore;
import com.sentinelpay.payment.config.RoutingProperties;
import com.sentinelpay.payment.domain.Provider;
import com.sentinelpay.payment.infrastructure.metrics.RoutingMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoutingEngineTest {

    private RoutingProperties properties;
    private ProviderHealthStore healthStore;
    private ProviderCircuitBreakers circuitBreakers;
    private RoutingEngine engine;

    @BeforeEach
    void setUp() {
        properties = new RoutingProperties();
        healthStore = mock(ProviderHealthStore.class);
        circuitBreakers = mock(ProviderCircuitBreakers.class);
        when(healthStore.read(any())).thenReturn(ProviderHealthStore.ProviderHealthView.neutral());
        when(circuitBreakers.stateName(any())).thenReturn("DISABLED");
        engine = new RoutingEngine(
                properties,
                healthStore,
                circuitBreakers,
                List.of(new StaticRankingPolicy(), new ScoredRankingPolicy()),
                new RoutingMetrics(new SimpleMeterRegistry()));
    }

    @Test
    void disabled_fallsBackToStaticOrder() {
        properties.setEnabled(false);
        RoutingDecision decision = engine.decide(sampleContext());

        assertThat(decision.policy()).isEqualTo("static");
        assertThat(decision.orderedProviders()).containsExactly(Provider.MOCKPAY, Provider.STRIPE);
        assertThat(decision.flags()).contains(RoutingDecision.FLAG_FALLBACK_STATIC);
    }

    @Test
    void merchantDeny_excludesProvider() {
        RoutingContext context = sampleContext();
        RoutingProperties.RuleSet rules = new RoutingProperties.RuleSet();
        rules.setDeny(List.of("mockpay"));
        properties.getMerchantRules().getMerchants().put(context.merchantId().toString(), rules);

        RoutingDecision decision = engine.decide(context);

        assertThat(decision.orderedProviders()).containsExactly(Provider.STRIPE);
        assertThat(decision.rationaleByProvider().get(Provider.MOCKPAY).matchedRules()).contains("RULE_DENY");
    }

    @Test
    void trafficSplit_isDeterministicByPaymentId() {
        properties.setSplit(Map.of("stripe", 100));
        RoutingContext ctx = new RoutingContext(
                UUID.fromString("00000000-0000-4000-8000-000000000001"),
                UUID.randomUUID(),
                100,
                "USD");

        RoutingDecision first = engine.decide(ctx);
        RoutingDecision second = engine.decide(ctx);

        assertThat(first.orderedProviders().get(0)).isEqualTo(second.orderedProviders().get(0));
    }

    private static RoutingContext sampleContext() {
        return new RoutingContext(UUID.randomUUID(), UUID.randomUUID(), 2_500, "USD");
    }
}
