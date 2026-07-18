package com.sentinelpay.payment.infrastructure.redis;

import com.sentinelpay.payment.application.port.ProviderHealthStore;
import com.sentinelpay.payment.config.RoutingProperties;
import com.sentinelpay.payment.domain.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class RedisProviderHealthStore implements ProviderHealthStore {

    private static final Logger log = LoggerFactory.getLogger(RedisProviderHealthStore.class);
    private static final String HEALTH_PREFIX = "routing:health:";
    private static final String LATENCY_PREFIX = "routing:latency:";
    private static final String FIELD_SUCCESS = "success";
    private static final String FIELD_FAILURE = "failure";

    private final StringRedisTemplate redis;
    private final RoutingProperties routingProperties;

    public RedisProviderHealthStore(StringRedisTemplate redis, RoutingProperties routingProperties) {
        this.redis = redis;
        this.routingProperties = routingProperties;
    }

    @Override
    public void recordOutcome(Provider provider, boolean success, long latencyMs) {
        try {
            recordHealthBucket(provider, success);
            updateLatencyEwma(provider, latencyMs);
        } catch (DataAccessException ex) {
            log.warn("Redis routing health write failed for {} — skipping (fail-open)", provider, ex);
        }
    }

    @Override
    public ProviderHealthView read(Provider provider) {
        try {
            long successes = 0L;
            long failures = 0L;
            int windowMinutes = routingProperties.getHealthWindowMinutes();
            long currentMinute = Instant.now().getEpochSecond() / 60L;
            List<String> keys = new ArrayList<>(windowMinutes);
            for (int i = 0; i < windowMinutes; i++) {
                keys.add(healthKey(provider, currentMinute - i));
            }
            for (String key : keys) {
                Map<Object, Object> entries = redis.opsForHash().entries(key);
                successes += parseCount(entries.get(FIELD_SUCCESS));
                failures += parseCount(entries.get(FIELD_FAILURE));
            }
            long total = successes + failures;
            double successRate = total == 0 ? 0.5 : (double) successes / total;
            long ewmaMs = parseLong(redis.opsForValue().get(latencyKey(provider)));
            return new ProviderHealthView(successRate, ewmaMs, false);
        } catch (DataAccessException ex) {
            log.warn("Redis routing health read failed for {} — neutral prior (fail-open)", provider, ex);
            return ProviderHealthView.degradedNeutral();
        }
    }

    private void recordHealthBucket(Provider provider, boolean success) {
        long minute = Instant.now().getEpochSecond() / 60L;
        String key = healthKey(provider, minute);
        String field = success ? FIELD_SUCCESS : FIELD_FAILURE;
        int ttlSeconds = (routingProperties.getHealthWindowMinutes() + 1) * 60;
        redis.execute(RedisScripts.HINCR_WITH_EXPIRE, List.of(key), field, String.valueOf(ttlSeconds));
    }

    private void updateLatencyEwma(Provider provider, long latencyMs) {
        String key = latencyKey(provider);
        double alpha = routingProperties.getLatencyEwmaAlpha();
        String existing = redis.opsForValue().get(key);
        double old = existing == null ? latencyMs : Double.parseDouble(existing);
        double updated = alpha * latencyMs + (1.0 - alpha) * old;
        redis.opsForValue().set(key, String.valueOf(Math.round(updated)));
    }

    static String healthKey(Provider provider, long epochMinute) {
        return HEALTH_PREFIX + provider.dbValue().toLowerCase(Locale.ROOT) + ":" + epochMinute;
    }

    static String latencyKey(Provider provider) {
        return LATENCY_PREFIX + provider.dbValue().toLowerCase(Locale.ROOT);
    }

    private static long parseCount(Object value) {
        if (value == null) {
            return 0L;
        }
        return Long.parseLong(value.toString());
    }

    private static long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        return Long.parseLong(value);
    }
}
