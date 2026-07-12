package com.sentinelpay.risk.application.model;

/**
 * Sliding velocity windows keyed as {@code fraud:velocity:{window}:{email}}.
 */
public enum VelocityWindow {

    ONE_HOUR("1h", 3600),
    TWENTY_FOUR_HOURS("24h", 86400);

    private final String keySegment;
    private final int ttlSeconds;

    VelocityWindow(String keySegment, int ttlSeconds) {
        this.keySegment = keySegment;
        this.ttlSeconds = ttlSeconds;
    }

    public String keySegment() {
        return keySegment;
    }

    public int ttlSeconds() {
        return ttlSeconds;
    }
}
