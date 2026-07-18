package com.sentinelpay.payment.application;

import com.sentinelpay.payment.PaymentTestContainers;
import com.sentinelpay.payment.application.model.ChargeCommand;
import com.sentinelpay.payment.application.model.ChargeResult;
import com.sentinelpay.payment.application.model.ProviderBehavior;
import com.sentinelpay.payment.application.model.ProviderOutcome.Outcome;
import com.sentinelpay.payment.application.port.ProviderCircuitBreakers;
import com.sentinelpay.payment.application.port.RiskEvaluator;
import com.sentinelpay.payment.application.routing.ProviderRoutingRationale;
import com.sentinelpay.payment.domain.PaymentStatus;
import com.sentinelpay.payment.domain.Provider;
import com.sentinelpay.payment.infrastructure.outbox.PaymentOutboxRepository;
import com.sentinelpay.payment.infrastructure.persistence.IdempotencyKeyRepository;
import com.sentinelpay.payment.infrastructure.persistence.PaymentAttemptRepository;
import com.sentinelpay.payment.infrastructure.persistence.PaymentRepository;
import com.sentinelpay.payment.infrastructure.persistence.PaymentStatusHistoryRepository;
import com.sentinelpay.payment.infrastructure.persistence.RefundRepository;
import com.sentinelpay.payment.infrastructure.provider.MockPayProvider;
import com.sentinelpay.payment.infrastructure.provider.StripeStubProvider;
import com.sentinelpay.payment.infrastructure.rest.DecisionTrail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(
        properties = {
            "sentinelpay.routing.policy=bandit",
            "sentinelpay.routing.breaker.enabled=true",
            "sentinelpay.routing.breaker.sliding-window-size=5",
            "sentinelpay.routing.breaker.failure-rate-threshold=50",
            "sentinelpay.routing.breaker.minimum-number-of-calls=5",
            "sentinelpay.routing.breaker.wait-duration-open-ms=2000"
        })
class RoutingShiftIT {

    @Autowired
    ProviderCircuitBreakers providerCircuitBreakers;

    @Autowired
    ChargeService chargeService;

    @Autowired
    TrailService trailService;

    @Autowired
    ProviderBehavior providerBehavior;

    @Autowired
    MockPayProvider mockPayProvider;

    @Autowired
    StripeStubProvider stripeStubProvider;

    @Autowired
    PaymentRepository paymentRepository;

    @Autowired
    PaymentAttemptRepository paymentAttemptRepository;

    @Autowired
    PaymentStatusHistoryRepository paymentStatusHistoryRepository;

    @Autowired
    PaymentOutboxRepository paymentOutboxRepository;

    @Autowired
    IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    RefundRepository refundRepository;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PaymentTestContainers.register(registry);
    }

    @BeforeEach
    void reset() {
        paymentOutboxRepository.deleteAll();
        refundRepository.deleteAll();
        paymentAttemptRepository.deleteAll();
        paymentStatusHistoryRepository.deleteAll();
        idempotencyKeyRepository.deleteAll();
        paymentRepository.deleteAll();
        providerBehavior.reset();
        mockPayProvider.resetCounters();
        stripeStubProvider.resetCounters();
    }

    @Test
    void mockPayOutage_shiftsToStripe_thenRecoveryProbe() throws InterruptedException {
        for (int i = 0; i < 5; i++) {
            providerCircuitBreakers.recordOutcome(Provider.MOCKPAY, false, 10);
        }
        assertThat(providerCircuitBreakers.stateName(Provider.MOCKPAY)).isEqualTo("OPEN");

        providerBehavior.program(Provider.MOCKPAY, Outcome.AUTHORIZED);
        providerBehavior.program(Provider.STRIPE, Outcome.AUTHORIZED);

        ChargeResult shifted = chargeService.charge(command("breaker-shift"));
        DecisionTrail shiftTrail = trailService.trail(shifted.paymentId());

        assertThat(shiftTrail.routing().policy()).isEqualTo("bandit");
        assertThat(shiftTrail.routing().orderedProviders().get(0)).isEqualTo("stripe");
        ProviderRoutingRationale mockpayRationale = shiftTrail.routing().providers().get("mockpay");
        assertThat(mockpayRationale).isNotNull();
        assertThat(mockpayRationale.breakerState()).isEqualTo("OPEN");
        assertThat(shiftTrail.attempts()).isNotEmpty();
        assertThat(shiftTrail.attempts().get(0).provider()).isEqualToIgnoringCase("stripe");

        providerBehavior.program(Provider.MOCKPAY, Outcome.AUTHORIZED);
        Thread.sleep(3_000L);

        ChargeResult probe = chargeService.charge(command("breaker-probe"));
        DecisionTrail probeTrail = trailService.trail(probe.paymentId());
        ProviderRoutingRationale probeMockpay = probeTrail.routing().providers().get("mockpay");
        assertThat(probeMockpay).isNotNull();
        assertThat(probeMockpay.breakerState()).isIn("HALF_OPEN", "CLOSED");

        providerBehavior.program(Provider.MOCKPAY, Outcome.AUTHORIZED);
        for (int i = 0; i < 8; i++) {
            chargeService.charge(command("recovery-" + i));
        }
        ChargeResult recovered = chargeService.charge(command("recovery-final"));
        DecisionTrail recoveredTrail = trailService.trail(recovered.paymentId());
        assertThat(recoveredTrail.routing().orderedProviders()).contains("mockpay");
        assertThat(recovered.status()).isEqualTo(PaymentStatus.COMPLETED);
    }

    private ChargeCommand command(String idempotencyKey) {
        return new ChargeCommand(
                UUID.randomUUID(), 2500, "USD", "buyer@example.com", idempotencyKey, "corr-" + idempotencyKey);
    }

    @TestConfiguration
    static class StubRiskConfig {

        @Bean
        @Primary
        RiskEvaluator stubRiskEvaluator() {
            return input -> new RiskEvaluator.RiskDecision(
                    0.0, "APPROVE", List.of(), "test-stub", false);
        }
    }
}
