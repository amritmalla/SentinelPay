package com.sentinelpay.provider.infrastructure.redis;

import com.sentinelpay.provider.application.model.ProviderHealthSnapshot;
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
class RedisProviderHealthWindowIT {

    private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:7-alpine");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(REDIS_IMAGE).withExposedPorts(6379);

    private StringRedisTemplate redisTemplate;
    private RedisProviderHealthWindow window;

    @BeforeEach
    void setUp() {
        LettuceConnectionFactory factory =
                new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        factory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(factory);
        redisTemplate.afterPropertiesSet();
        window = new RedisProviderHealthWindow(redisTemplate, 60, 0.5);
    }

    @Test
    void recordOutcome_setsTtlOnFirstWrite() {
        window.recordOutcome("stripe", true);

        Long ttl = redisTemplate.getExpire(RedisProviderHealthWindow.key("stripe"));
        assertThat(ttl).isPositive().isLessThanOrEqualTo(60L);
    }

    @Test
    void read_missTreatsProviderAsHealthyDegraded() {
        ProviderHealthSnapshot snapshot = window.read("mockpay");

        assertThat(snapshot.healthy()).isTrue();
        assertThat(snapshot.degraded()).isTrue();
    }

    @Test
    void read_highFailureRate_marksUnhealthy() {
        window.recordOutcome("stripe", false);
        window.recordOutcome("stripe", false);
        window.recordOutcome("stripe", true);

        ProviderHealthSnapshot snapshot = window.read("stripe");

        assertThat(snapshot.degraded()).isFalse();
        assertThat(snapshot.healthy()).isFalse();
        assertThat(snapshot.failureCount()).isEqualTo(2);
    }

    @Test
    void read_redisUnavailable_treatsProviderAsHealthyDegraded() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory("127.0.0.1", 6399);
        factory.afterPropertiesSet();
        StringRedisTemplate broken = new StringRedisTemplate(factory);
        broken.afterPropertiesSet();
        RedisProviderHealthWindow brokenWindow = new RedisProviderHealthWindow(broken, 60, 0.5);

        ProviderHealthSnapshot snapshot = brokenWindow.read("stripe");

        assertThat(snapshot).isEqualTo(ProviderHealthSnapshot.degradedHealthy());
    }
}
