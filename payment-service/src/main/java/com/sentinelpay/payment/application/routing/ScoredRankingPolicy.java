package com.sentinelpay.payment.application.routing;

import com.sentinelpay.payment.application.port.ProviderHealthStore;
import com.sentinelpay.payment.config.RoutingProperties;
import com.sentinelpay.payment.domain.Provider;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * score = wS·successRate + wL·latencyScore; cost tie-break within epsilon.
 */
public final class ScoredRankingPolicy implements RankingPolicy {

    private final Map<Provider, ScoredSnapshot> lastRanked = new HashMap<>();

    @Override
    public String name() {
        return "scored";
    }

    @Override
    public List<Provider> rank(
            RoutingContext context,
            List<Provider> candidates,
            ProviderHealthStore healthStore,
            RoutingProperties properties) {
        lastRanked.clear();
        RoutingProperties.Scored scored = properties.getScored();
        double wSuccess = scored.getWeightSuccess();
        double wLatency = scored.getWeightLatency();
        long ceilingMs = properties.getLatencyCeilingMs();

        record ScoredProvider(Provider provider, double score, ScoredSnapshot snapshot) {
        }

        List<ScoredProvider> scoredList = candidates.stream()
                .map(provider -> {
                    ProviderHealthStore.ProviderHealthView health = healthStore.read(provider);
                    ScoredSnapshot snapshot = computeScore(provider, health, properties, wSuccess, wLatency, ceilingMs);
                    lastRanked.put(provider, snapshot);
                    return new ScoredProvider(provider, snapshot.score(), snapshot);
                })
                .toList();

        if (isColdStart(candidates, healthStore)) {
            return candidates.stream()
                    .sorted(Comparator.comparingInt(StaticRankingPolicy::staticIndex))
                    .toList();
        }

        scoredList = scoredList.stream()
                .sorted(Comparator
                        .comparingDouble(ScoredProvider::score).reversed()
                        .thenComparingInt(p -> StaticRankingPolicy.staticIndex(p.provider()))
                        .thenComparingLong(p -> normalizedFeeCents(p.provider(), properties)))
                .toList();

        return scoredList.stream().map(ScoredProvider::provider).toList();
    }

    private static boolean isColdStart(List<Provider> candidates, ProviderHealthStore healthStore) {
        return candidates.stream().allMatch(provider -> {
            ProviderHealthStore.ProviderHealthView health = healthStore.read(provider);
            return !health.degraded()
                    && health.latencyEwmaMs() == 0L
                    && Math.abs(health.successRate() - 0.5) < 1e-9;
        });
    }

    @Override
    public ProviderRoutingRationale rationaleFor(
            Provider provider,
            int rank,
            ProviderHealthStore.ProviderHealthView health,
            RoutingProperties properties) {
        ScoredSnapshot snapshot = lastRanked.containsKey(provider)
                ? lastRanked.get(provider)
                : computeScore(
                        provider,
                        health,
                        properties,
                        properties.getScored().getWeightSuccess(),
                        properties.getScored().getWeightLatency(),
                        properties.getLatencyCeilingMs());
        return ProviderRoutingRationale.ranked(
                List.of(),
                snapshot.successRate(),
                snapshot.latencyEwmaMs(),
                snapshot.score(),
                snapshot.components(),
                rank);
    }

    ScoredSnapshot snapshotFor(Provider provider) {
        return lastRanked.get(provider);
    }

    static ScoredSnapshot computeScore(
            Provider provider,
            ProviderHealthStore.ProviderHealthView health,
            RoutingProperties properties,
            double wSuccess,
            double wLatency,
            long ceilingMs) {
        double successRate = health.degraded() || health.latencyEwmaMs() == 0L && health.successRate() == 0.5
                ? 0.5
                : health.successRate();
        long ewmaMs = health.latencyEwmaMs();
        double latencyScore = ceilingMs <= 0
                ? 1.0
                : clamp(1.0 - (double) ewmaMs / ceilingMs, 0.0, 1.0);
        double successComponent = wSuccess * successRate;
        double latencyComponent = wLatency * latencyScore;
        double score = successComponent + latencyComponent;
        var components = new ProviderRoutingRationale.ScoreComponents(successComponent, latencyComponent, null);
        return new ScoredSnapshot(score, successRate, ewmaMs, components);
    }

    static long normalizedFeeCents(Provider provider, RoutingProperties properties) {
        RoutingProperties.ProviderConfig cfg = properties.getProviders().get(provider.dbValue());
        if (cfg == null) {
            return Long.MAX_VALUE;
        }
        return cfg.getFeeFixedCents() + cfg.getFeeBps();
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    record ScoredSnapshot(
            double score,
            double successRate,
            long latencyEwmaMs,
            ProviderRoutingRationale.ScoreComponents components) {
    }
}
