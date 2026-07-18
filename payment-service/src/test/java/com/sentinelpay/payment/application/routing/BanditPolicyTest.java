package com.sentinelpay.payment.application.routing;

import com.sentinelpay.payment.application.port.ProviderBanditStore;
import com.sentinelpay.payment.application.port.ProviderHealthStore;
import com.sentinelpay.payment.config.RoutingProperties;
import com.sentinelpay.payment.domain.Provider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BanditPolicyTest {

    private final InMemoryBanditStore banditStore = new InMemoryBanditStore();
    private final BanditRankingPolicy policy = new BanditRankingPolicy(banditStore);
    private final ProviderHealthStore healthStore = mock(ProviderHealthStore.class);
    private RoutingProperties properties;

    @BeforeEach
    void setUp() {
        properties = new RoutingProperties();
        when(healthStore.read(any())).thenReturn(ProviderHealthStore.ProviderHealthView.neutral());
    }

    @Test
    void posteriorUpdate_incrementsAlphaOnSuccess() {
        banditStore.recordOutcome(Provider.MOCKPAY, true);
        banditStore.recordOutcome(Provider.MOCKPAY, true);
        banditStore.recordOutcome(Provider.MOCKPAY, false);

        ProviderBanditStore.Posterior posterior = banditStore.read(Provider.MOCKPAY);

        assertThat(posterior.alpha()).isEqualTo(3.0);
        assertThat(posterior.beta()).isEqualTo(2.0);
        assertThat(posterior.mean()).isEqualTo(0.6);
    }

    @Test
    void seededRng_samePaymentId_producesIdenticalRank() {
        banditStore.seed(Provider.MOCKPAY, 10.0, 2.0);
        banditStore.seed(Provider.STRIPE, 2.0, 10.0);
        UUID paymentId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        RoutingContext context = new RoutingContext(paymentId, UUID.randomUUID(), 1_000, "USD");

        List<Provider> first = policy.rank(context, List.of(Provider.MOCKPAY, Provider.STRIPE), healthStore, properties).ordered();
        List<Provider> second = policy.rank(context, List.of(Provider.MOCKPAY, Provider.STRIPE), healthStore, properties).ordered();

        assertThat(second).containsExactlyElementsOf(first);
    }

    @Test
    void costPenalty_prefersCheaperProviderAtEqualPosterior() {
        properties.getBandit().setCostWeight(1.0);
        banditStore.seed(Provider.MOCKPAY, 5.0, 5.0);
        banditStore.seed(Provider.STRIPE, 5.0, 5.0);

        UUID paymentId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        RoutingContext context = new RoutingContext(paymentId, UUID.randomUUID(), 1_000, "USD");
        List<Provider> ranked = policy.rank(context, List.of(Provider.MOCKPAY, Provider.STRIPE), healthStore, properties).ordered();

        assertThat(ranked.get(0)).isEqualTo(Provider.MOCKPAY);
    }

    @Test
    void convergence_betterProviderFirstAtLeast95Percent() {
        Random outcomeRng = new Random(42L);
        for (int i = 0; i < 200; i++) {
            banditStore.recordOutcome(Provider.MOCKPAY, outcomeRng.nextDouble() < 0.9);
            banditStore.recordOutcome(Provider.STRIPE, outcomeRng.nextDouble() < 0.4);
        }

        int mockPayFirst = 0;
        int samples = 500;
        for (int i = 0; i < samples; i++) {
            UUID paymentId = UUID.nameUUIDFromBytes(("payment-" + i).getBytes());
            RoutingContext context = new RoutingContext(paymentId, UUID.randomUUID(), 1_000, "USD");
            List<Provider> ranked =
                    policy.rank(context, List.of(Provider.MOCKPAY, Provider.STRIPE), healthStore, properties).ordered();
            if (ranked.get(0) == Provider.MOCKPAY) {
                mockPayFirst++;
            }
        }

        assertThat(mockPayFirst)
                .as("MockPay (90%% success) should be first choice in >=95%% of Thompson samples")
                .isGreaterThanOrEqualTo((int) (samples * 0.95));
    }

    @Test
    void rationale_includesBanditFields() {
        banditStore.seed(Provider.MOCKPAY, 8.0, 2.0);
        UUID paymentId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        RoutingContext context = new RoutingContext(paymentId, UUID.randomUUID(), 1_000, "USD");
        RankingPolicy.RankingResult result =
                policy.rank(context, List.of(Provider.MOCKPAY, Provider.STRIPE), healthStore, properties);

        ProviderRoutingRationale rationale = policy.rationaleFor(
                Provider.MOCKPAY,
                1,
                ProviderHealthStore.ProviderHealthView.neutral(),
                properties,
                result.snapshots().get(Provider.MOCKPAY));

        assertThat(rationale.bandit()).isNotNull();
        assertThat(rationale.bandit().alpha()).isEqualTo(8.0);
        assertThat(rationale.bandit().beta()).isEqualTo(2.0);
        assertThat(rationale.bandit().posteriorMean()).isEqualTo(0.8);
        assertThat(rationale.bandit().sampledTheta()).isBetween(0.0, 1.0);
    }

    private static final class InMemoryBanditStore implements ProviderBanditStore {

        private final Map<Provider, double[]> posteriors = new EnumMap<>(Provider.class);

        @Override
        public Posterior read(Provider provider) {
            double[] state = posteriors.getOrDefault(provider, new double[] {1.0, 1.0});
            return new Posterior(state[0], state[1], false);
        }

        @Override
        public void recordOutcome(Provider provider, boolean success) {
            double[] state = posteriors.computeIfAbsent(provider, ignored -> new double[] {1.0, 1.0});
            if (success) {
                state[0] += 1.0;
            } else {
                state[1] += 1.0;
            }
        }

        void seed(Provider provider, double alpha, double beta) {
            posteriors.put(provider, new double[] {alpha, beta});
        }
    }
}
