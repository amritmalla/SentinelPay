package com.sentinelpay.payment.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.TimeoutOptions;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.time.Duration;

@Configuration
@Import(RedisAutoConfiguration.class)
public class RedisConfig {

    /**
     * Routing reads (health/bandit) sit on the synchronous charge path but are fail-open: a Redis
     * outage must degrade to the static order in milliseconds, never stall the charge. Lettuce's
     * default 60s command timeout with command queuing during reconnect would hang the whole charge.
     * A short command timeout plus REJECT_COMMANDS makes a down/blipping Redis surface as an immediate
     * DataAccessException, which the stores catch and fall back on.
     */
    @Bean
    LettuceClientConfigurationBuilderCustomizer routingRedisFailFast() {
        return builder -> builder
                .commandTimeout(Duration.ofMillis(250))
                .clientOptions(ClientOptions.builder()
                        .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                        .timeoutOptions(TimeoutOptions.enabled(Duration.ofMillis(250)))
                        .build());
    }
}
