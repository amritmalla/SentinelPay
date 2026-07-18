package com.sentinelpay.payment.infrastructure.resilience;

import com.sentinelpay.payment.config.RoutingProperties;
import com.sentinelpay.payment.domain.Provider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Resilience4jProviderCircuitBreakersTest {

    private Resilience4jProviderCircuitBreakers breakers;

    @BeforeEach
    void setUp() {
        RoutingProperties properties = new RoutingProperties();
        properties.getBreaker().setEnabled(true);
        properties.getBreaker().setSlidingWindowSize(4);
        properties.getBreaker().setFailureRateThreshold(50);
        properties.getBreaker().setMinimumNumberOfCalls(4);
        properties.getBreaker().setPermittedCallsHalfOpen(1);
        properties.getBreaker().setWaitDurationOpenMs(60_000);
        breakers = new Resilience4jProviderCircuitBreakers(properties, new SimpleMeterRegistry());
    }

    @Test
    void repeatedFailures_opensBreaker() {
        for (int i = 0; i < 4; i++) {
            breakers.recordOutcome(Provider.MOCKPAY, false, 10);
        }

        assertThat(breakers.stateName(Provider.MOCKPAY)).isEqualTo("OPEN");
    }

    @Test
    void successes_keepBreakerClosed() {
        for (int i = 0; i < 4; i++) {
            breakers.recordOutcome(Provider.STRIPE, true, 10);
        }

        assertThat(breakers.stateName(Provider.STRIPE)).isEqualTo("CLOSED");
    }

    @Test
    void disabledConfig_reportsDisabledState() {
        RoutingProperties properties = new RoutingProperties();
        properties.getBreaker().setEnabled(false);
        Resilience4jProviderCircuitBreakers disabled =
                new Resilience4jProviderCircuitBreakers(properties, new SimpleMeterRegistry());

        assertThat(disabled.stateName(Provider.MOCKPAY)).isEqualTo("DISABLED");
    }
}
