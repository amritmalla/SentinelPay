package com.sentinelpay.provider.application.model;

/**
 * @param healthy       advisory routing signal; true when Redis is missing or failure rate is acceptable
 * @param successCount  successes in the rolling window (0 when degraded miss)
 * @param failureCount  failures in the rolling window (0 when degraded miss)
 * @param degraded      true when Redis was unavailable or the key was absent after an error
 */
public record ProviderHealthSnapshot(
        boolean healthy,
        long successCount,
        long failureCount,
        boolean degraded) {

    public static ProviderHealthSnapshot degradedHealthy() {
        return new ProviderHealthSnapshot(true, 0, 0, true);
    }

    public static ProviderHealthSnapshot fromCounts(long successes, long failures, double unhealthyThreshold) {
        long total = successes + failures;
        if (total == 0) {
            return new ProviderHealthSnapshot(true, 0, 0, false);
        }
        double failureRate = (double) failures / total;
        return new ProviderHealthSnapshot(failureRate < unhealthyThreshold, successes, failures, false);
    }
}
