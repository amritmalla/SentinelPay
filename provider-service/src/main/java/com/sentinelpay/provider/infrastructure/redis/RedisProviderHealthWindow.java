package com.sentinelpay.provider.infrastructure.redis;

import com.sentinelpay.provider.application.model.ProviderHealthSnapshot;
import com.sentinelpay.provider.application.port.ProviderHealthWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class RedisProviderHealthWindow implements ProviderHealthWindow {

    private static final Logger log = LoggerFactory.getLogger(RedisProviderHealthWindow.class);
    private static final String KEY_PREFIX = "provider:health:";
    private static final String FIELD_SUCCESS = "success";
    private static final String FIELD_FAILURE = "failure";

    private final StringRedisTemplate redis;
    private final int windowTtlSeconds;
    private final double unhealthyThreshold;

    public RedisProviderHealthWindow(
            StringRedisTemplate redis,
            @Value("${sentinelpay.provider.health.window-ttl-seconds:60}") int windowTtlSeconds,
            @Value("${sentinelpay.provider.health.unhealthy-failure-rate:0.5}") double unhealthyThreshold) {
        this.redis = redis;
        this.windowTtlSeconds = windowTtlSeconds;
        this.unhealthyThreshold = unhealthyThreshold;
    }

    @Override
    public void recordOutcome(String provider, boolean success) {
        String key = key(provider);
        String field = success ? FIELD_SUCCESS : FIELD_FAILURE;
        try {
            redis.execute(
                    RedisScripts.HINCR_WITH_EXPIRE,
                    List.of(key),
                    field,
                    String.valueOf(windowTtlSeconds));
        } catch (DataAccessException ex) {
            log.warn("Redis provider health write failed for key {} — routing may be degraded", key, ex);
        }
    }

    @Override
    public ProviderHealthSnapshot read(String provider) {
        String key = key(provider);
        try {
            Map<Object, Object> entries = redis.opsForHash().entries(key);
            if (entries.isEmpty()) {
                return ProviderHealthSnapshot.degradedHealthy();
            }
            long successes = parseCount(entries.get(FIELD_SUCCESS));
            long failures = parseCount(entries.get(FIELD_FAILURE));
            return ProviderHealthSnapshot.fromCounts(successes, failures, unhealthyThreshold);
        } catch (DataAccessException ex) {
            log.warn("Redis provider health read failed for key {} — treating provider as healthy (degraded)", key, ex);
            return ProviderHealthSnapshot.degradedHealthy();
        }
    }

    private static long parseCount(Object value) {
        if (value == null) {
            return 0L;
        }
        return Long.parseLong(value.toString());
    }

    static String key(String provider) {
        return KEY_PREFIX + provider.trim().toLowerCase(Locale.ROOT);
    }
}
