package com.sentinelpay.payment.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Validated
@ConfigurationProperties(prefix = "sentinelpay.routing")
public class RoutingProperties {

    private boolean enabled = true;

    @NotBlank
    private String policy = "scored";

    @Min(1)
    @Max(60)
    private int healthWindowMinutes = 5;

    @Min(100)
    private long latencyCeilingMs = 2000L;

    @Min(0)
    @Max(1)
    private double latencyEwmaAlpha = 0.3;

    @Valid
    @NotNull
    private Scored scored = new Scored();

    @Valid
    @NotNull
    private Bandit bandit = new Bandit();

    @Valid
    @NotNull
    private Breaker breaker = new Breaker();

    /** Percentage split over ranked head, e.g. stripe: 10 → 10% Stripe-first canary. */
    private Map<String, Integer> split = new HashMap<>();

    @Valid
    @NotNull
    private Map<String, ProviderConfig> providers = defaultProviders();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getPolicy() {
        return policy;
    }

    public void setPolicy(String policy) {
        this.policy = policy;
    }

    public int getHealthWindowMinutes() {
        return healthWindowMinutes;
    }

    public void setHealthWindowMinutes(int healthWindowMinutes) {
        this.healthWindowMinutes = healthWindowMinutes;
    }

    public long getLatencyCeilingMs() {
        return latencyCeilingMs;
    }

    public void setLatencyCeilingMs(long latencyCeilingMs) {
        this.latencyCeilingMs = latencyCeilingMs;
    }

    public double getLatencyEwmaAlpha() {
        return latencyEwmaAlpha;
    }

    public void setLatencyEwmaAlpha(double latencyEwmaAlpha) {
        this.latencyEwmaAlpha = latencyEwmaAlpha;
    }

    public Scored getScored() {
        return scored;
    }

    public void setScored(Scored scored) {
        this.scored = scored != null ? scored : new Scored();
    }

    public Bandit getBandit() {
        return bandit;
    }

    public void setBandit(Bandit bandit) {
        this.bandit = bandit != null ? bandit : new Bandit();
    }

    public Breaker getBreaker() {
        return breaker;
    }

    public void setBreaker(Breaker breaker) {
        this.breaker = breaker != null ? breaker : new Breaker();
    }

    public Map<String, Integer> getSplit() {
        return split;
    }

    public void setSplit(Map<String, Integer> split) {
        this.split = split != null ? split : new HashMap<>();
    }

    public Map<String, ProviderConfig> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, ProviderConfig> providers) {
        this.providers = providers != null && !providers.isEmpty() ? providers : defaultProviders();
    }

    private static Map<String, ProviderConfig> defaultProviders() {
        Map<String, ProviderConfig> map = new HashMap<>();
        map.put("mockpay", new ProviderConfig());
        map.put("stripe", new ProviderConfig());
        map.get("stripe").setFeeBps(290);
        map.get("stripe").setFeeFixedCents(30);
        return map;
    }

    public static class Scored {
        @Min(0)
        @Max(1)
        private double weightSuccess = 0.7;

        @Min(0)
        @Max(1)
        private double weightLatency = 0.3;

        @Min(0)
        @Max(1)
        private double costTiebreakEpsilon = 0.05;

        public double getWeightSuccess() {
            return weightSuccess;
        }

        public void setWeightSuccess(double weightSuccess) {
            this.weightSuccess = weightSuccess;
        }

        public double getWeightLatency() {
            return weightLatency;
        }

        public void setWeightLatency(double weightLatency) {
            this.weightLatency = weightLatency;
        }

        public double getCostTiebreakEpsilon() {
            return costTiebreakEpsilon;
        }

        public void setCostTiebreakEpsilon(double costTiebreakEpsilon) {
            this.costTiebreakEpsilon = costTiebreakEpsilon;
        }
    }

    public static class Bandit {
        @Min(1)
        private int decayHalfLifeMinutes = 30;

        @Min(0)
        @Max(1)
        private double costWeight = 0.1;

        public int getDecayHalfLifeMinutes() {
            return decayHalfLifeMinutes;
        }

        public void setDecayHalfLifeMinutes(int decayHalfLifeMinutes) {
            this.decayHalfLifeMinutes = decayHalfLifeMinutes;
        }

        public double getCostWeight() {
            return costWeight;
        }

        public void setCostWeight(double costWeight) {
            this.costWeight = costWeight;
        }
    }

    public static class Breaker {
        private boolean enabled = false;

        @Min(1)
        private int slidingWindowSize = 20;

        @Min(1)
        @Max(100)
        private int failureRateThreshold = 50;

        @Min(1000)
        private long waitDurationOpenMs = 30_000L;

        @Min(1)
        private int permittedCallsHalfOpen = 3;

        @Min(1)
        private int minimumNumberOfCalls = 10;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getSlidingWindowSize() {
            return slidingWindowSize;
        }

        public void setSlidingWindowSize(int slidingWindowSize) {
            this.slidingWindowSize = slidingWindowSize;
        }

        public int getFailureRateThreshold() {
            return failureRateThreshold;
        }

        public void setFailureRateThreshold(int failureRateThreshold) {
            this.failureRateThreshold = failureRateThreshold;
        }

        public long getWaitDurationOpenMs() {
            return waitDurationOpenMs;
        }

        public void setWaitDurationOpenMs(long waitDurationOpenMs) {
            this.waitDurationOpenMs = waitDurationOpenMs;
        }

        public int getPermittedCallsHalfOpen() {
            return permittedCallsHalfOpen;
        }

        public void setPermittedCallsHalfOpen(int permittedCallsHalfOpen) {
            this.permittedCallsHalfOpen = permittedCallsHalfOpen;
        }

        public int getMinimumNumberOfCalls() {
            return minimumNumberOfCalls;
        }

        public void setMinimumNumberOfCalls(int minimumNumberOfCalls) {
            this.minimumNumberOfCalls = minimumNumberOfCalls;
        }
    }

    public static class ProviderConfig {
        private boolean enabled = true;

        @Min(0)
        private int feeBps = 150;

        @Min(0)
        private int feeFixedCents = 10;

        private Set<String> currencies = new HashSet<>(Set.of("USD"));

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getFeeBps() {
            return feeBps;
        }

        public void setFeeBps(int feeBps) {
            this.feeBps = feeBps;
        }

        public int getFeeFixedCents() {
            return feeFixedCents;
        }

        public void setFeeFixedCents(int feeFixedCents) {
            this.feeFixedCents = feeFixedCents;
        }

        public Set<String> getCurrencies() {
            return currencies;
        }

        public void setCurrencies(Set<String> currencies) {
            this.currencies = currencies != null ? currencies : new HashSet<>();
        }

        public boolean supportsCurrency(String currency) {
            if (currency == null || currency.isBlank()) {
                return false;
            }
            String normalized = currency.trim().toUpperCase(Locale.ROOT);
            return currencies.isEmpty() || currencies.contains(normalized);
        }
    }
}
