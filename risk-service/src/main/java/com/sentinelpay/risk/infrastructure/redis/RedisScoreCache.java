package com.sentinelpay.risk.infrastructure.redis;

import com.sentinelpay.risk.application.model.ScoreCacheEntry;
import com.sentinelpay.risk.application.port.ScoreCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class RedisScoreCache implements ScoreCache {

    private static final Logger log = LoggerFactory.getLogger(RedisScoreCache.class);
    private static final String KEY_PREFIX = "fraud:score:";
    private static final Duration TTL = Duration.ofSeconds(300);

    private final StringRedisTemplate redis;

    public RedisScoreCache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void put(String featureHash, String scoreJson) {
        String key = key(featureHash);
        try {
            redis.opsForValue().set(key, scoreJson, TTL);
        } catch (DataAccessException ex) {
            log.warn("Redis score cache write failed for key {} — scorer will recompute", key, ex);
        }
    }

    @Override
    public ScoreCacheEntry get(String featureHash) {
        String key = key(featureHash);
        try {
            String json = redis.opsForValue().get(key);
            if (json == null) {
                return ScoreCacheEntry.miss();
            }
            return ScoreCacheEntry.hit(json);
        } catch (DataAccessException ex) {
            log.warn("Redis score cache read failed for key {} — will recompute (degraded)", key, ex);
            return ScoreCacheEntry.degradedMiss();
        }
    }

    static String key(String featureHash) {
        return KEY_PREFIX + featureHash;
    }
}
