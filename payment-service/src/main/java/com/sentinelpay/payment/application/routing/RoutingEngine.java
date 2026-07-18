package com.sentinelpay.payment.application.routing;

import com.sentinelpay.payment.application.port.ProviderCircuitBreakers;
import com.sentinelpay.payment.application.port.ProviderHealthStore;
import com.sentinelpay.payment.config.MerchantCountryProperties;
import com.sentinelpay.payment.config.MerchantRoutingRules;
import com.sentinelpay.payment.config.RoutingProperties;
import com.sentinelpay.payment.domain.Provider;
import com.sentinelpay.payment.infrastructure.metrics.RoutingMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class RoutingEngine {

    private static final Logger log = LoggerFactory.getLogger(RoutingEngine.class);
    private static final List<Provider> BASE_ORDER = List.of(Provider.MOCKPAY, Provider.STRIPE);

    private final RoutingProperties properties;
    private final MerchantCountryProperties merchantProperties;
    private final ProviderHealthStore healthStore;
    private final ProviderCircuitBreakers circuitBreakers;
    private final Map<String, RankingPolicy> policies;
    private final RoutingMetrics routingMetrics;

    public RoutingEngine(
            RoutingProperties properties,
            MerchantCountryProperties merchantProperties,
            ProviderHealthStore healthStore,
            ProviderCircuitBreakers circuitBreakers,
            List<RankingPolicy> rankingPolicies,
            RoutingMetrics routingMetrics) {
        this.properties = properties;
        this.merchantProperties = merchantProperties;
        this.healthStore = healthStore;
        this.circuitBreakers = circuitBreakers;
        this.policies = rankingPolicies.stream()
                .collect(Collectors.toMap(RankingPolicy::name, p -> p, (a, b) -> a));
        this.routingMetrics = routingMetrics;
    }

    public RoutingDecision decide(RoutingContext context) {
        if (!properties.isEnabled()) {
            routingMetrics.recordFallback();
            return staticFallback("disabled");
        }
        try {
            MerchantRoutingRules.RuleSet merchantRules =
                    merchantProperties.getRoutingRules().resolve(context.merchantId());
            List<Provider> candidates = new ArrayList<>(BASE_ORDER);
            Map<Provider, ProviderRoutingRationale> rationale = new LinkedHashMap<>();

            Stage1Result stage1 = applyEligibility(context, candidates, merchantRules);
            candidates = stage1.eligible();
            stage1.ineligible().forEach(rationale::put);

            if (candidates.isEmpty()) {
                return staticFallback("no_eligible_providers");
            }

            BreakerStageResult breakerStage = applyCircuitBreaker(candidates);
            Map<Provider, String> breakerStates = breakerStage.breakerStates();
            breakerStage.removed().forEach((provider, state) ->
                    rationale.put(provider, ProviderRoutingRationale.breakerExcluded(state)));
            candidates = breakerStage.admitted();

            List<String> flags = new ArrayList<>();
            if (breakerStage.bypassed()) {
                flags.add(RoutingDecision.FLAG_BREAKERS_BYPASSED);
                routingMetrics.recordBreakerBypass();
            }

            RankingPolicy policy = resolvePolicy();
            RankingPolicy.RankingResult rankingResult = policy.rank(context, candidates, healthStore, properties);
            List<Provider> ranked = pinHalfOpenLast(rankingResult.ordered(), breakerStates);
            ranked = applyPreferPin(merchantRules, ranked, stage1.matchedRules());

            TrafficSplitResult splitResult = applyTrafficSplit(context, ranked);
            ranked = splitResult.ranked();
            if (splitResult.applied()) {
                flags.add(RoutingDecision.FLAG_SPLIT_ASSIGNED);
                stage1
                        .matchedRules()
                        .computeIfAbsent(splitResult.assignedProvider(), ignored -> new ArrayList<>())
                        .add("SPLIT_ASSIGNED");
            }

            for (int i = 0; i < ranked.size(); i++) {
                Provider provider = ranked.get(i);
                ProviderHealthStore.ProviderHealthView health = healthStore.read(provider);
                List<String> rules = stage1.matchedRules().getOrDefault(provider, List.of());
                ProviderRoutingRationale base = policy.rationaleFor(
                        provider, i + 1, health, properties, rankingResult.snapshots().get(provider));
                rationale.put(
                        provider,
                        mergeRules(base.withBreakerState(breakerStates.get(provider)), rules));
            }

            RoutingDecision decision = new RoutingDecision(
                    policy.name(), ranked, rationale, List.copyOf(flags), splitResult.assignment());
            recordMetrics(decision);
            return decision;
        } catch (RuntimeException ex) {
            log.warn("Routing engine error — falling back to static order", ex);
            routingMetrics.recordFallback();
            return staticFallback("engine_error");
        }
    }

    private RankingPolicy resolvePolicy() {
        RankingPolicy policy = policies.get(properties.getPolicy());
        if (policy == null) {
            log.warn("Unknown routing policy {} — using static", properties.getPolicy());
            return policies.get("static");
        }
        return policy;
    }

    private Stage1Result applyEligibility(
            RoutingContext context, List<Provider> candidates, MerchantRoutingRules.RuleSet rules) {
        String currency = context.currency();

        List<Provider> eligible = new ArrayList<>();
        Map<Provider, ProviderRoutingRationale> ineligible = new LinkedHashMap<>();
        Map<Provider, List<String>> matchedRules = new HashMap<>();

        for (Provider provider : candidates) {
            List<String> applied = new ArrayList<>();
            RoutingProperties.ProviderConfig cfg = properties.getProviders().get(provider.dbValue());
            if (cfg == null || !cfg.isEnabled()) {
                ineligible.put(provider, ProviderRoutingRationale.ineligible(List.of("PROVIDER_DISABLED")));
                continue;
            }
            if (!cfg.supportsCurrency(currency)) {
                ineligible.put(provider, ProviderRoutingRationale.ineligible(List.of("CURRENCY_UNSUPPORTED")));
                continue;
            }
            if (rules.getDeny().stream().anyMatch(d -> d.equalsIgnoreCase(provider.dbValue()))) {
                applied.add("RULE_DENY");
                ineligible.put(provider, ProviderRoutingRationale.ineligible(applied));
                continue;
            }
            Long maxAmount = rules.getMaxAmountCents().get(provider.dbValue());
            if (maxAmount != null && context.amountCents() > maxAmount) {
                applied.add("RULE_MAX_AMOUNT");
                ineligible.put(provider, ProviderRoutingRationale.ineligible(applied));
                continue;
            }
            matchedRules.put(provider, applied);
            eligible.add(provider);
        }

        return new Stage1Result(eligible, ineligible, matchedRules);
    }

    private static List<Provider> applyPreferPin(
            MerchantRoutingRules.RuleSet rules, List<Provider> ranked, Map<Provider, List<String>> matchedRules) {
        if (rules.getPrefer() == null || rules.getPrefer().isBlank()) {
            return ranked;
        }
        String prefer = rules.getPrefer().trim().toLowerCase(Locale.ROOT);
        Provider preferred = Provider.fromDbValue(prefer);
        if (!ranked.contains(preferred)) {
            return ranked;
        }
        matchedRules.computeIfAbsent(preferred, ignored -> new ArrayList<>()).add("RULE_PREFER");
        List<Provider> reordered = new ArrayList<>();
        reordered.add(preferred);
        ranked.stream().filter(p -> p != preferred).forEach(reordered::add);
        return reordered;
    }

    private BreakerStageResult applyCircuitBreaker(List<Provider> candidates) {
        if (!properties.getBreaker().isEnabled()) {
            Map<Provider, String> disabled = new LinkedHashMap<>();
            candidates.forEach(p -> disabled.put(p, "DISABLED"));
            return BreakerStageResult.admitted(candidates, disabled, Map.of(), false);
        }

        Map<Provider, String> states = new LinkedHashMap<>();
        List<Provider> closed = new ArrayList<>();
        List<Provider> halfOpen = new ArrayList<>();
        List<Provider> open = new ArrayList<>();

        for (Provider provider : candidates) {
            String state = circuitBreakers.stateName(provider);
            states.put(provider, state);
            switch (state) {
                case "OPEN" -> open.add(provider);
                case "HALF_OPEN" -> halfOpen.add(provider);
                default -> closed.add(provider);
            }
        }

        if (closed.isEmpty() && halfOpen.isEmpty()) {
            return BreakerStageResult.admitted(candidates, states, Map.of(), true);
        }

        Map<Provider, String> removed = new LinkedHashMap<>();
        open.forEach(p -> removed.put(p, "OPEN"));

        List<Provider> admitted = new ArrayList<>(closed);
        admitted.addAll(halfOpen);
        return BreakerStageResult.admitted(admitted, states, removed, false);
    }

    private static List<Provider> pinHalfOpenLast(List<Provider> ranked, Map<Provider, String> breakerStates) {
        List<Provider> halfOpen = ranked.stream()
                .filter(p -> "HALF_OPEN".equals(breakerStates.get(p)))
                .toList();
        if (halfOpen.isEmpty()) {
            return ranked;
        }
        List<Provider> reordered = new ArrayList<>();
        ranked.stream().filter(p -> !"HALF_OPEN".equals(breakerStates.get(p))).forEach(reordered::add);
        reordered.addAll(halfOpen);
        return reordered;
    }

    private TrafficSplitResult applyTrafficSplit(RoutingContext context, List<Provider> ranked) {
        Map<String, Integer> split = properties.getSplit();
        if (split == null || split.isEmpty() || ranked.size() < 2) {
            return TrafficSplitResult.unchanged(ranked);
        }
        int bucket = splitBucket(context.paymentId());
        int cumulative = 0;
        for (Map.Entry<String, Integer> entry : split.entrySet()) {
            int pct = Math.max(0, Math.min(100, entry.getValue()));
            cumulative += pct;
            if (bucket < cumulative) {
                Provider assigned = Provider.fromDbValue(entry.getKey().toLowerCase(Locale.ROOT));
                if (!ranked.contains(assigned)) {
                    return TrafficSplitResult.unchanged(ranked);
                }
                List<Provider> reordered = new ArrayList<>();
                reordered.add(assigned);
                ranked.stream().filter(p -> p != assigned).forEach(reordered::add);
                return TrafficSplitResult.assigned(
                        reordered,
                        new RoutingDecision.SplitAssignment(bucket, assigned.dbValue()));
            }
        }
        return TrafficSplitResult.unchanged(ranked);
    }

    static int splitBucket(java.util.UUID paymentId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(paymentId.toString().getBytes(StandardCharsets.UTF_8));
            int value = ((hash[0] & 0xFF) << 8) | (hash[1] & 0xFF);
            return value % 100;
        } catch (NoSuchAlgorithmException ex) {
            return Math.abs(paymentId.hashCode()) % 100;
        }
    }

    private RoutingDecision staticFallback(String reason) {
        Map<Provider, ProviderRoutingRationale> rationale = new LinkedHashMap<>();
        for (int i = 0; i < BASE_ORDER.size(); i++) {
            rationale.put(
                    BASE_ORDER.get(i),
                    ProviderRoutingRationale.ranked(List.of(), 0.5, 0L, 0.0, null, i + 1));
        }
        RoutingDecision decision = new RoutingDecision(
                "static",
                BASE_ORDER,
                rationale,
                List.of(RoutingDecision.FLAG_FALLBACK_STATIC));
        routingMetrics.recordDecision(decision, reason);
        return decision;
    }

    private void recordMetrics(RoutingDecision decision) {
        routingMetrics.recordDecision(decision, "ok");
        decision.rationaleByProvider().forEach((provider, rationale) -> {
            if (rationale.bandit() != null) {
                routingMetrics.recordProviderScore(provider, rationale.bandit().posteriorMean());
            } else if (rationale.score() != null) {
                routingMetrics.recordProviderScore(provider, rationale.score());
            }
        });
    }

    private static ProviderRoutingRationale mergeRules(ProviderRoutingRationale base, List<String> rules) {
        if (rules == null || rules.isEmpty()) {
            return base;
        }
        List<String> merged = new ArrayList<>(rules);
        if (base.matchedRules() != null) {
            merged.addAll(base.matchedRules());
        }
        return new ProviderRoutingRationale(
                base.eligible(),
                merged,
                base.breakerState(),
                base.successRate(),
                base.latencyEwmaMs(),
                base.score(),
                base.scoreComponents(),
                base.rank(),
                base.bandit());
    }

    private record Stage1Result(
            List<Provider> eligible,
            Map<Provider, ProviderRoutingRationale> ineligible,
            Map<Provider, List<String>> matchedRules) {
    }

    private record BreakerStageResult(
            List<Provider> admitted,
            Map<Provider, String> breakerStates,
            Map<Provider, String> removed,
            boolean bypassed) {

        static BreakerStageResult admitted(
                List<Provider> admitted,
                Map<Provider, String> breakerStates,
                Map<Provider, String> removed,
                boolean bypassed) {
            return new BreakerStageResult(admitted, breakerStates, removed, bypassed);
        }
    }

    private record TrafficSplitResult(
            List<Provider> ranked, RoutingDecision.SplitAssignment assignment, boolean applied, Provider assignedProvider) {

        static TrafficSplitResult unchanged(List<Provider> ranked) {
            return new TrafficSplitResult(ranked, null, false, null);
        }

        static TrafficSplitResult assigned(List<Provider> ranked, RoutingDecision.SplitAssignment assignment) {
            return new TrafficSplitResult(
                    ranked, assignment, true, Provider.fromDbValue(assignment.assignedProvider()));
        }
    }
}
