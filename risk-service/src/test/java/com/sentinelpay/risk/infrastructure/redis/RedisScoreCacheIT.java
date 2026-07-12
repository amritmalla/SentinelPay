package com.sentinelpay.risk.infrastructure.redis;

import com.sentinelpay.risk.application.model.ScoreCacheEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class RedisScoreCacheIT {

    private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:7-alpine");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(REDIS_IMAGE).withExposedPorts(6379);

    private StringRedisTemplate redisTemplate;
    private RedisScoreCache cache;

    @BeforeEach
    void setUp() {
        LettuceConnectionFactory factory =
                new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        factory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(factory);
        redisTemplate.afterPropertiesSet();
        cache = new RedisScoreCache(redisTemplate);
    }

    @Test
    void put_setsTtl() {
        String hash = "abc123";
        cache.put(hash, "{\"score\":0.12}");

        Long ttl = redisTemplate.getExpire(RedisScoreCache.key(hash));
        assertThat(ttl).isPositive().isLessThanOrEqualTo(300L);
    }

    @Test
    void get_hitReturnsJson() {
        String hash = "feature-hash";
        cache.put(hash, "{\"score\":0.42}");

        ScoreCacheEntry entry = cache.get(hash);

        assertThat(entry.degraded()).isFalse();
        assertThat(entry.scoreJson()).contains("{\"score\":0.42}");
    }

    @Test
    void get_redisUnavailable_returnsDegradedMiss() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory("127.0.0.1", 6399);
        factory.afterPropertiesSet();
        StringRedisTemplate broken = new StringRedisTemplate(factory);
        broken.afterPropertiesSet();
        RedisScoreCache brokenCache = new RedisScoreCache(broken);

        ScoreCacheEntry entry = brokenCache.get("any-hash");

        assertThat(entry).isEqualTo(ScoreCacheEntry.degradedMiss());
    }
}
