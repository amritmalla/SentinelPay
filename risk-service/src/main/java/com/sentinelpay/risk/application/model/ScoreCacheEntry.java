package com.sentinelpay.risk.application.model;

import java.util.Optional;

/**
 * @param scoreJson cached scorer JSON, empty on miss or Redis error
 * @param degraded  true when Redis was unavailable or the key was absent after an error path
 */
public record ScoreCacheEntry(Optional<String> scoreJson, boolean degraded) {

    public static ScoreCacheEntry miss() {
        return new ScoreCacheEntry(Optional.empty(), false);
    }

    public static ScoreCacheEntry degradedMiss() {
        return new ScoreCacheEntry(Optional.empty(), true);
    }

    public static ScoreCacheEntry hit(String json) {
        return new ScoreCacheEntry(Optional.of(json), false);
    }
}
