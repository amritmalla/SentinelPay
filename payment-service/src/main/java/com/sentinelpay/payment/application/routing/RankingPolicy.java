package com.sentinelpay.payment.application.routing;

import com.sentinelpay.payment.application.port.ProviderHealthStore;
import com.sentinelpay.payment.config.RoutingProperties;
import com.sentinelpay.payment.domain.Provider;

import java.util.List;
import java.util.Map;

public interface RankingPolicy {

    String name();

    RankingResult rank(
            RoutingContext context,
            List<Provider> candidates,
            ProviderHealthStore healthStore,
            RoutingProperties properties);

    ProviderRoutingRationale rationaleFor(
            Provider provider,
            int rank,
            ProviderHealthStore.ProviderHealthView health,
            RoutingProperties properties,
            Snapshot snapshot);

    /** Marker for policy-specific per-decision data computed during {@link #rank}. */
    interface Snapshot {
    }

    /**
     * Ranking output plus the per-provider snapshots that produced it. Snapshots travel with the
     * result (not on the policy bean) so concurrent decisions can never observe each other's state.
     */
    record RankingResult(List<Provider> ordered, Map<Provider, Snapshot> snapshots) {

        static RankingResult of(List<Provider> ordered) {
            return new RankingResult(ordered, Map.of());
        }
    }
}
