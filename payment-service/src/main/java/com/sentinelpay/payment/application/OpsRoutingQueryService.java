package com.sentinelpay.payment.application;

import com.sentinelpay.payment.application.port.ProviderBanditStore;
import com.sentinelpay.payment.application.port.ProviderCircuitBreakers;
import com.sentinelpay.payment.application.port.ProviderHealthStore;
import com.sentinelpay.payment.config.RoutingProperties;
import com.sentinelpay.payment.domain.Provider;
import com.sentinelpay.payment.infrastructure.rest.ops.OpsRoutingConfigResponse;
import com.sentinelpay.payment.infrastructure.rest.ops.OpsRoutingProvidersResponse;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

@Service
public class OpsRoutingQueryService {

    private final ProviderHealthStore providerHealthStore;
    private final ProviderBanditStore providerBanditStore;
    private final ProviderCircuitBreakers providerCircuitBreakers;
    private final RoutingProperties routingProperties;

    public OpsRoutingQueryService(
            ProviderHealthStore providerHealthStore,
            ProviderBanditStore providerBanditStore,
            ProviderCircuitBreakers providerCircuitBreakers,
            RoutingProperties routingProperties) {
        this.providerHealthStore = providerHealthStore;
        this.providerBanditStore = providerBanditStore;
        this.providerCircuitBreakers = providerCircuitBreakers;
        this.routingProperties = routingProperties;
    }

    public OpsRoutingProvidersResponse providers() {
        boolean degraded = false;
        List<OpsRoutingProvidersResponse.OpsProviderView> providers = Arrays.stream(Provider.values())
                .map(this::toProviderView)
                .toList();
        for (OpsRoutingProvidersResponse.OpsProviderView provider : providers) {
            if (provider.health().degraded() || provider.bandit().degraded()) {
                degraded = true;
                break;
            }
        }
        return new OpsRoutingProvidersResponse(degraded, providers);
    }

    public OpsRoutingConfigResponse config() {
        return OpsRoutingConfigResponse.from(routingProperties);
    }

    private OpsRoutingProvidersResponse.OpsProviderView toProviderView(Provider provider) {
        ProviderHealthStore.ProviderHealthView health = providerHealthStore.read(provider);
        ProviderBanditStore.Posterior bandit = providerBanditStore.read(provider);
        RoutingProperties.ProviderConfig config =
                routingProperties.getProviders().getOrDefault(provider.dbValue(), new RoutingProperties.ProviderConfig());

        return new OpsRoutingProvidersResponse.OpsProviderView(
                provider.dbValue(),
                config.isEnabled(),
                providerCircuitBreakers.stateName(provider),
                new OpsRoutingProvidersResponse.OpsHealthView(
                        health.successRate(), health.latencyEwmaMs(), health.degraded()),
                new OpsRoutingProvidersResponse.OpsBanditView(
                        bandit.alpha(), bandit.beta(), bandit.mean(), bandit.degraded()),
                new OpsRoutingProvidersResponse.OpsFeeView(config.getFeeBps(), config.getFeeFixedCents()));
    }
}
