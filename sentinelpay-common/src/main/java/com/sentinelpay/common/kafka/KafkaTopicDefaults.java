package com.sentinelpay.common.kafka;

import java.util.Map;

public final class KafkaTopicDefaults {

    /** Seven-day retention per ADR-0011. */
    public static final String RETENTION_MS = "604800000";

    public static Map<String, String> retentionConfig() {
        return Map.of("retention.ms", RETENTION_MS);
    }

    private KafkaTopicDefaults() {
    }
}
