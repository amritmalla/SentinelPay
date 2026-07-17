package com.sentinelpay.payment.application.routing;

import com.sentinelpay.payment.application.port.ProviderHealthStore;
import com.sentinelpay.payment.config.RoutingProperties;
import com.sentinelpay.payment.domain.Provider;

import java.util.List;

public interface RankingPolicy {

    String name();

    List<Provider> rank(
            RoutingContext context,
            List<Provider> candidates,
            ProviderHealthStore healthStore,
            RoutingProperties properties);

    ProviderRoutingRationale rationaleFor(
            Provider provider,
            int rank,
            ProviderHealthStore.ProviderHealthView health,
            RoutingProperties properties);
}
