package com.sentinelpay.risk.infrastructure.redis;

import com.sentinelpay.risk.application.model.VelocityReadResult;
import com.sentinelpay.risk.application.model.VelocityWindow;
import com.sentinelpay.risk.application.port.VelocityStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
public class RedisVelocityStore implements VelocityStore {

    private static final Logger log = LoggerFactory.getLogger(RedisVelocityStore.class);
    private static final String KEY_PREFIX = "fraud:velocity:";

    private final StringRedisTemplate redis;

    public RedisVelocityStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public long increment(String email, VelocityWindow window) {
        String key = key(email, window);
        try {
            Long value = redis.execute(
                    RedisScripts.INCR_WITH_EXPIRE,
                    List.of(key),
                    String.valueOf(window.ttlSeconds()));
            return value != null ? value : 0L;
        } catch (DataAccessException ex) {
            log.warn("Redis velocity increment failed for key {} — scoring may be degraded", key, ex);
            return 0L;
        }
    }

    @Override
    public VelocityReadResult getCount(String email, VelocityWindow window) {
        String key = key(email, window);
        try {
            String raw = redis.opsForValue().get(key);
            if (raw == null) {
                return VelocityReadResult.ok(0L);
            }
            return VelocityReadResult.ok(Long.parseLong(raw));
        } catch (DataAccessException ex) {
            log.warn("Redis velocity read failed for key {} — returning 0 (degraded)", key, ex);
            return VelocityReadResult.degradedZero();
        } catch (NumberFormatException ex) {
            log.warn("Invalid velocity counter at key {} — returning 0 (degraded)", key, ex);
            return VelocityReadResult.degradedZero();
        }
    }

    static String key(String email, VelocityWindow window) {
        return KEY_PREFIX + window.keySegment() + ":" + email.trim().toLowerCase(Locale.ROOT);
    }
}
