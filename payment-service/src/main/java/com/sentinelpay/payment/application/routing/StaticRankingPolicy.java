package com.sentinelpay.payment.application.routing;

import com.sentinelpay.payment.application.port.ProviderHealthStore;
import com.sentinelpay.payment.config.RoutingProperties;
import com.sentinelpay.payment.domain.Provider;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic v1 order — kill switch and CI baseline.
 */
public final class StaticRankingPolicy implements RankingPolicy {

    private static final List<Provider> STATIC_ORDER = List.of(Provider.MOCKPAY, Provider.STRIPE);

    @Override
    public String name() {
        return "static";
    }

    @Override
    public RankingResult rank(
            RoutingContext context,
            List<Provider> candidates,
            ProviderHealthStore healthStore,
            RoutingProperties properties) {
        return RankingResult.of(candidates.stream()
                .sorted(Comparator.comparingInt(StaticRankingPolicy::staticIndex))
                .toList());
    }

    @Override
    public ProviderRoutingRationale rationaleFor(
            Provider provider,
            int rank,
            ProviderHealthStore.ProviderHealthView health,
            RoutingProperties properties,
            Snapshot snapshot) {
        return ProviderRoutingRationale.ranked(List.of(), health.successRate(), health.latencyEwmaMs(), 0.0, null, rank);
    }

    static int staticIndex(Provider provider) {
        int idx = STATIC_ORDER.indexOf(provider);
        return idx < 0 ? Integer.MAX_VALUE : idx;
    }
}
