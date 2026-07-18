package com.sentinelpay.payment.infrastructure.redis;

import com.sentinelpay.payment.application.port.ProviderBanditStore;
import com.sentinelpay.payment.config.RoutingProperties;
import com.sentinelpay.payment.domain.Provider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@Testcontainers
class RedisProviderBanditStoreIT {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private RedisProviderBanditStore store;
    private StringRedisTemplate template;

    @BeforeEach
    void setUp() {
        LettuceConnectionFactory factory =
                new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        factory.afterPropertiesSet();
        template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        template.getConnectionFactory().getConnection().serverCommands().flushAll();

        RoutingProperties properties = new RoutingProperties();
        properties.getBandit().setDecayHalfLifeMinutes(30);
        store = new RedisProviderBanditStore(template, properties);
    }

    @Test
    void read_withoutData_returnsPrior() {
        ProviderBanditStore.Posterior posterior = store.read(Provider.MOCKPAY);

        assertThat(posterior.alpha()).isEqualTo(1.0);
        assertThat(posterior.beta()).isEqualTo(1.0);
        assertThat(posterior.mean()).isEqualTo(0.5);
    }

    @Test
    void recordOutcome_updatesPosterior() {
        store.recordOutcome(Provider.STRIPE, true);
        store.recordOutcome(Provider.STRIPE, true);
        store.recordOutcome(Provider.STRIPE, false);

        ProviderBanditStore.Posterior posterior = store.read(Provider.STRIPE);

        assertThat(posterior.alpha()).isEqualTo(3.0);
        assertThat(posterior.beta()).isEqualTo(2.0);
    }

    @Test
    void decay_pullsPosteriorTowardPrior() {
        template.opsForHash().put(RedisProviderBanditStore.key(Provider.MOCKPAY), "alpha", "50.0");
        template.opsForHash().put(RedisProviderBanditStore.key(Provider.MOCKPAY), "beta", "10.0");
        long halfLifeMs = 30L * 60_000L;
        long stale = System.currentTimeMillis() - (12L * halfLifeMs);
        template.opsForHash().put(RedisProviderBanditStore.key(Provider.MOCKPAY), "last_decay_ms", String.valueOf(stale));

        ProviderBanditStore.Posterior posterior = store.read(Provider.MOCKPAY);

        assertThat(posterior.mean()).isCloseTo(0.5, within(0.02));
        assertThat(posterior.alpha()).isCloseTo(1.0, within(0.05));
        assertThat(posterior.beta()).isCloseTo(1.0, within(0.05));
    }
}
