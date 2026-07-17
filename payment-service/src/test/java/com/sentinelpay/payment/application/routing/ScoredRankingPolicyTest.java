package com.sentinelpay.payment.application.routing;

import com.sentinelpay.payment.application.port.ProviderHealthStore;
import com.sentinelpay.payment.config.RoutingProperties;
import com.sentinelpay.payment.domain.Provider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScoredRankingPolicyTest {

    private final ScoredRankingPolicy policy = new ScoredRankingPolicy();
    private final ProviderHealthStore healthStore = mock(ProviderHealthStore.class);
    private RoutingProperties properties;
    private RoutingContext context;

    @BeforeEach
    void setUp() {
        properties = new RoutingProperties();
        context = new RoutingContext(UUID.randomUUID(), UUID.randomUUID(), 2_500, "USD");
    }

    @Test
    void coldStart_reproducesStaticOrder() {
        when(healthStore.read(any())).thenReturn(ProviderHealthStore.ProviderHealthView.neutral());
        List<Provider> ranked = policy.rank(
                context, List.of(Provider.MOCKPAY, Provider.STRIPE), healthStore, properties);

        assertThat(ranked).containsExactly(Provider.MOCKPAY, Provider.STRIPE);
    }

    @Test
    void higherSuccessRate_ranksFirst() {
        when(healthStore.read(Provider.MOCKPAY))
                .thenReturn(new ProviderHealthStore.ProviderHealthView(0.2, 100L, false));
        when(healthStore.read(Provider.STRIPE))
                .thenReturn(new ProviderHealthStore.ProviderHealthView(0.95, 100L, false));

        List<Provider> ranked = policy.rank(
                context, List.of(Provider.MOCKPAY, Provider.STRIPE), healthStore, properties);

        assertThat(ranked.get(0)).isEqualTo(Provider.STRIPE);
    }

    @Test
    void equalScores_useStaticOrderTieBreak() {
        when(healthStore.read(Provider.MOCKPAY))
                .thenReturn(new ProviderHealthStore.ProviderHealthView(0.8, 50L, false));
        when(healthStore.read(Provider.STRIPE))
                .thenReturn(new ProviderHealthStore.ProviderHealthView(0.8, 50L, false));

        List<Provider> ranked = policy.rank(
                context, List.of(Provider.MOCKPAY, Provider.STRIPE), healthStore, properties);

        assertThat(ranked).containsExactly(Provider.MOCKPAY, Provider.STRIPE);
    }
}
