package com.sentinelpay.payment.infrastructure.redis;

import com.sentinelpay.payment.application.port.ProviderHealthStore;
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

@Testcontainers
class RedisProviderHealthStoreIT {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private RedisProviderHealthStore store;

    @BeforeEach
    void setUp() {
        LettuceConnectionFactory factory =
                new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        factory.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        template.getConnectionFactory().getConnection().serverCommands().flushAll();

        RoutingProperties properties = new RoutingProperties();
        properties.setHealthWindowMinutes(5);
        properties.setLatencyEwmaAlpha(0.5);
        store = new RedisProviderHealthStore(template, properties);
    }

    @Test
    void recordAndRead_successRateAndEwma() {
        store.recordOutcome(Provider.MOCKPAY, true, 100);
        store.recordOutcome(Provider.MOCKPAY, false, 300);

        ProviderHealthStore.ProviderHealthView view = store.read(Provider.MOCKPAY);

        assertThat(view.successRate()).isEqualTo(0.5);
        assertThat(view.latencyEwmaMs()).isBetween(150L, 250L);
    }

    @Test
    void read_withoutData_returnsNeutralPrior() {
        ProviderHealthStore.ProviderHealthView view = store.read(Provider.STRIPE);

        assertThat(view.successRate()).isEqualTo(0.5);
        assertThat(view.latencyEwmaMs()).isZero();
    }
}
