package com.sentinelpay.risk.application.model;

/**
 * @param count    transaction count in the window (0 when Redis is unavailable)
 * @param degraded true when the read fell back due to Redis miss or error
 */
public record VelocityReadResult(long count, boolean degraded) {

    public static VelocityReadResult degradedZero() {
        return new VelocityReadResult(0, true);
    }

    public static VelocityReadResult ok(long count) {
        return new VelocityReadResult(count, false);
    }
}
