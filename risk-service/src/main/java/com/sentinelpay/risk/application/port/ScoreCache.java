package com.sentinelpay.risk.application.port;

import com.sentinelpay.risk.application.model.ScoreCacheEntry;

/**
 * Short-lived cache of deterministic rule-scorer output. Backed by Redis; miss triggers recompute.
 */
public interface ScoreCache {

    void put(String featureHash, String scoreJson);

    ScoreCacheEntry get(String featureHash);
}
