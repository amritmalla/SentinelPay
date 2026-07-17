package com.sentinelpay.payment.infrastructure.resilience;

import com.sentinelpay.payment.application.port.ProviderCircuitBreakers;
import com.sentinelpay.payment.config.RoutingProperties;
import com.sentinelpay.payment.domain.Provider;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerOnStateTransitionEvent;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class Resilience4jProviderCircuitBreakers implements ProviderCircuitBreakers {

    private static final Logger log = LoggerFactory.getLogger(Resilience4jProviderCircuitBreakers.class);
    private static final String DISABLED = "DISABLED";

    private final RoutingProperties routingProperties;
    private final Map<Provider, CircuitBreaker> breakers;

    public Resilience4jProviderCircuitBreakers(RoutingProperties routingProperties, MeterRegistry meterRegistry) {
        this.routingProperties = routingProperties;
        CircuitBreakerRegistry registry = buildRegistry();
        this.breakers = new EnumMap<>(Provider.class);
        for (Provider provider : Provider.values()) {
            CircuitBreaker breaker = registry.circuitBreaker(provider.dbValue());
            breaker.getEventPublisher().onStateTransition(this::logTransition);
            breakers.put(provider, breaker);
        }
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meterRegistry);
    }

    @Override
    public String stateName(Provider provider) {
        if (!routingProperties.getBreaker().isEnabled()) {
            return DISABLED;
        }
        return breakers.get(provider).getState().name();
    }

    @Override
    public void recordOutcome(Provider provider, boolean success, long elapsedMs) {
        if (!routingProperties.getBreaker().isEnabled()) {
            return;
        }
        CircuitBreaker breaker = breakers.get(provider);
        long elapsed = Math.max(elapsedMs, 0L);
        if (success) {
            breaker.onSuccess(elapsed, TimeUnit.MILLISECONDS);
        } else {
            breaker.onError(elapsed, TimeUnit.MILLISECONDS, ProviderAttemptException.INSTANCE);
        }
    }

    private CircuitBreakerRegistry buildRegistry() {
        RoutingProperties.Breaker cfg = routingProperties.getBreaker();
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(cfg.getSlidingWindowSize())
                .failureRateThreshold(cfg.getFailureRateThreshold())
                .waitDurationInOpenState(Duration.ofMillis(cfg.getWaitDurationOpenMs()))
                .permittedNumberOfCallsInHalfOpenState(cfg.getPermittedCallsHalfOpen())
                .recordExceptions(ProviderAttemptException.class)
                .build();
        return CircuitBreakerRegistry.of(config);
    }

    private void logTransition(CircuitBreakerOnStateTransitionEvent event) {
        log.info(
                "routing breaker {} transitioned {} -> {}",
                event.getCircuitBreakerName(),
                event.getStateTransition().getFromState(),
                event.getStateTransition().getToState());
    }

    /** Sentinel exception type recorded on provider attempt failures. */
    static final class ProviderAttemptException extends RuntimeException {
        static final ProviderAttemptException INSTANCE = new ProviderAttemptException();

        private ProviderAttemptException() {
            super("provider attempt failed");
        }
    }
}
