package com.sentinelpay.risk.infrastructure.redis;

import com.sentinelpay.risk.application.model.VelocityReadResult;
import com.sentinelpay.risk.application.model.VelocityWindow;
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
class RedisVelocityStoreIT {

    private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:7-alpine");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(REDIS_IMAGE).withExposedPorts(6379);

    private StringRedisTemplate redisTemplate;
    private RedisVelocityStore store;

    @BeforeEach
    void setUp() {
        LettuceConnectionFactory factory =
                new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        factory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(factory);
        redisTemplate.afterPropertiesSet();
        store = new RedisVelocityStore(redisTemplate);
    }

    @Test
    void increment_setsTtlAtomically() {
        String email = "buyer@example.com";
        store.increment(email, VelocityWindow.ONE_HOUR);

        String key = RedisVelocityStore.key(email, VelocityWindow.ONE_HOUR);
        Long ttl = redisTemplate.getExpire(key);
        assertThat(ttl).isPositive().isLessThanOrEqualTo(VelocityWindow.ONE_HOUR.ttlSeconds());
    }

    @Test
    void getCount_missReturnsZeroWithoutDegradedFlag() {
        VelocityReadResult result = store.getCount("unknown@example.com", VelocityWindow.ONE_HOUR);

        assertThat(result.count()).isZero();
        assertThat(result.degraded()).isFalse();
    }

    @Test
    void getCount_redisUnavailable_returnsDegradedZero() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory("127.0.0.1", 6399);
        factory.afterPropertiesSet();
        StringRedisTemplate broken = new StringRedisTemplate(factory);
        broken.afterPropertiesSet();
        RedisVelocityStore brokenStore = new RedisVelocityStore(broken);

        VelocityReadResult result = brokenStore.getCount("buyer@example.com", VelocityWindow.ONE_HOUR);

        assertThat(result).isEqualTo(VelocityReadResult.degradedZero());
    }
}
