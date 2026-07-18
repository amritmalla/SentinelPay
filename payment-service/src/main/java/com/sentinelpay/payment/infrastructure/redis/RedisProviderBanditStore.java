package com.sentinelpay.payment.infrastructure.redis;

import com.sentinelpay.payment.application.port.ProviderBanditStore;
import com.sentinelpay.payment.config.RoutingProperties;
import com.sentinelpay.payment.domain.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;

@Component
public class RedisProviderBanditStore implements ProviderBanditStore {

    private static final Logger log = LoggerFactory.getLogger(RedisProviderBanditStore.class);
    private static final String PREFIX = "routing:bandit:";
    private static final String FIELD_ALPHA = "alpha";
    private static final String FIELD_BETA = "beta";
    private static final String FIELD_LAST_DECAY_MS = "last_decay_ms";

    private final StringRedisTemplate redis;
    private final RoutingProperties routingProperties;

    public RedisProviderBanditStore(StringRedisTemplate redis, RoutingProperties routingProperties) {
        this.redis = redis;
        this.routingProperties = routingProperties;
    }

    @Override
    public Posterior read(Provider provider) {
        try {
            return readAndDecay(provider);
        } catch (DataAccessException ex) {
            log.warn("Redis bandit read failed for {} — using prior (fail-open)", provider, ex);
            return Posterior.degradedPrior();
        }
    }

    @Override
    public void recordOutcome(Provider provider, boolean success) {
        try {
            Posterior current = readAndDecay(provider);
            double alpha = current.alpha() + (success ? 1.0 : 0.0);
            double beta = current.beta() + (success ? 0.0 : 1.0);
            String key = key(provider);
            redis.opsForHash().put(key, FIELD_ALPHA, format(alpha));
            redis.opsForHash().put(key, FIELD_BETA, format(beta));
        } catch (DataAccessException ex) {
            log.warn("Redis bandit write failed for {} — skipping (fail-open)", provider, ex);
        }
    }

    private Posterior readAndDecay(Provider provider) {
        String key = key(provider);
        Map<Object, Object> entries = redis.opsForHash().entries(key);
        if (entries.isEmpty()) {
            return Posterior.prior();
        }
        double alpha = parse(entries.get(FIELD_ALPHA), 1.0);
        double beta = parse(entries.get(FIELD_BETA), 1.0);
        long lastDecayMs = parseLong(entries.get(FIELD_LAST_DECAY_MS), Instant.now().toEpochMilli());

        DecayedPosterior decayed = applyDecay(alpha, beta, lastDecayMs);
        if (decayed.updated()) {
            redis.opsForHash().put(key, FIELD_ALPHA, format(decayed.alpha()));
            redis.opsForHash().put(key, FIELD_BETA, format(decayed.beta()));
            redis.opsForHash().put(key, FIELD_LAST_DECAY_MS, String.valueOf(decayed.lastDecayMs()));
        }
        return new Posterior(decayed.alpha(), decayed.beta(), false);
    }

    private DecayedPosterior applyDecay(double alpha, double beta, long lastDecayMs) {
        long halfLifeMs = routingProperties.getBandit().getDecayHalfLifeMinutes() * 60_000L;
        if (halfLifeMs <= 0) {
            return new DecayedPosterior(alpha, beta, lastDecayMs, false);
        }
        long now = Instant.now().toEpochMilli();
        long elapsed = Math.max(0L, now - lastDecayMs);
        if (elapsed < halfLifeMs) {
            return new DecayedPosterior(alpha, beta, lastDecayMs, false);
        }
        double periods = (double) elapsed / halfLifeMs;
        double factor = Math.pow(0.5, periods);
        double decayedAlpha = 1.0 + (alpha - 1.0) * factor;
        double decayedBeta = 1.0 + (beta - 1.0) * factor;
        return new DecayedPosterior(decayedAlpha, decayedBeta, now, true);
    }

    static String key(Provider provider) {
        return PREFIX + provider.dbValue().toLowerCase(Locale.ROOT);
    }

    private static double parse(Object value, double fallback) {
        if (value == null) {
            return fallback;
        }
        return Double.parseDouble(value.toString());
    }

    private static long parseLong(Object value, long fallback) {
        if (value == null) {
            return fallback;
        }
        return Long.parseLong(value.toString());
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private record DecayedPosterior(double alpha, double beta, long lastDecayMs, boolean updated) {
    }
}
