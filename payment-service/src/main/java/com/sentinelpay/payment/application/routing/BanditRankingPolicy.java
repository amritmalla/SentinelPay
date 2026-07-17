package com.sentinelpay.payment.application.routing;

import com.sentinelpay.payment.application.port.ProviderBanditStore;
import com.sentinelpay.payment.application.port.ProviderHealthStore;
import com.sentinelpay.payment.config.RoutingProperties;
import com.sentinelpay.payment.domain.Provider;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Thompson sampling: sample θ ~ Beta(α, β) per provider, rank by θ − costWeight·normalizedFee.
 * RNG is seeded from {@link RoutingContext#paymentId()} so idempotent replays rank identically.
 */
@Component
public final class BanditRankingPolicy implements RankingPolicy {

    private final ProviderBanditStore banditStore;
    private final Map<Provider, BanditSnapshot> lastRanked = new HashMap<>();

    public BanditRankingPolicy(ProviderBanditStore banditStore) {
        this.banditStore = banditStore;
    }

    @Override
    public String name() {
        return "bandit";
    }

    @Override
    public List<Provider> rank(
            RoutingContext context,
            List<Provider> candidates,
            ProviderHealthStore healthStore,
            RoutingProperties properties) {
        lastRanked.clear();
        double costWeight = properties.getBandit().getCostWeight();
        Random random = seededRandom(context.paymentId());

        record RankedProvider(Provider provider, double sampledTheta, double adjustedScore, BanditSnapshot snapshot) {
        }

        List<RankedProvider> ranked = candidates.stream()
                .map(provider -> {
                    ProviderBanditStore.Posterior posterior = banditStore.read(provider);
                    double sampled = BetaSampler.sample(posterior.alpha(), posterior.beta(), random);
                    double costPenalty = costWeight * normalizedFee(provider, properties);
                    double adjusted = sampled - costPenalty;
                    BanditSnapshot snapshot = new BanditSnapshot(
                            posterior.alpha(), posterior.beta(), posterior.mean(), sampled, costPenalty, adjusted);
                    lastRanked.put(provider, snapshot);
                    return new RankedProvider(provider, sampled, adjusted, snapshot);
                })
                .sorted(Comparator
                        .comparingDouble(RankedProvider::adjustedScore).reversed()
                        .thenComparingInt(p -> StaticRankingPolicy.staticIndex(p.provider())))
                .toList();

        return ranked.stream().map(RankedProvider::provider).toList();
    }

    @Override
    public ProviderRoutingRationale rationaleFor(
            Provider provider,
            int rank,
            ProviderHealthStore.ProviderHealthView health,
            RoutingProperties properties) {
        BanditSnapshot snapshot = lastRanked.get(provider);
        if (snapshot == null) {
            ProviderBanditStore.Posterior posterior = banditStore.read(provider);
            snapshot = new BanditSnapshot(
                    posterior.alpha(), posterior.beta(), posterior.mean(), posterior.mean(), 0.0, posterior.mean());
        }
        return ProviderRoutingRationale.banditRanked(snapshot.toRationale(), rank);
    }

    static Random seededRandom(java.util.UUID paymentId) {
        long seed = paymentId.getMostSignificantBits() ^ paymentId.getLeastSignificantBits();
        return new Random(seed);
    }

    static double normalizedFee(Provider provider, RoutingProperties properties) {
        long fee = ScoredRankingPolicy.normalizedFeeCents(provider, properties);
        return fee / 1000.0;
    }

    record BanditSnapshot(
            double alpha,
            double beta,
            double posteriorMean,
            double sampledTheta,
            double costPenalty,
            double adjustedScore) {

        ProviderRoutingRationale.BanditRationale toRationale() {
            return new ProviderRoutingRationale.BanditRationale(
                    alpha, beta, posteriorMean, sampledTheta, costPenalty);
        }
    }
}
