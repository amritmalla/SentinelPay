package com.sentinelpay.payment.application;

import com.sentinelpay.payment.PaymentTestContainers;
import com.sentinelpay.payment.application.model.ChargeCommand;
import com.sentinelpay.payment.application.model.ChargeResult;
import com.sentinelpay.payment.application.model.ProviderBehavior;
import com.sentinelpay.payment.application.model.ProviderOutcome.Outcome;
import com.sentinelpay.payment.application.port.RiskEvaluator;
import com.sentinelpay.payment.domain.PaymentStatus;
import com.sentinelpay.payment.domain.Provider;
import com.sentinelpay.payment.infrastructure.outbox.PaymentOutboxRepository;
import com.sentinelpay.payment.infrastructure.persistence.IdempotencyKeyRepository;
import com.sentinelpay.payment.infrastructure.persistence.PaymentAttemptRepository;
import com.sentinelpay.payment.infrastructure.persistence.PaymentRepository;
import com.sentinelpay.payment.infrastructure.persistence.PaymentStatusHistoryRepository;
import com.sentinelpay.payment.infrastructure.persistence.RefundRepository;
import com.sentinelpay.payment.infrastructure.provider.MockPayProvider;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Correctness suite against {@code StripeProvider} (mode=real) with Stripe API mocked by WireMock —
 * the path CI never exercised when only {@code StripeStubProvider} was active.
 */
@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
class FailoverChargeStripeProviderIT {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @Autowired
    ChargeService chargeService;

    @Autowired
    ProviderBehavior providerBehavior;

    @Autowired
    MockPayProvider mockPayProvider;

    @Autowired
    PaymentRepository paymentRepository;

    @Autowired
    PaymentAttemptRepository paymentAttemptRepository;

    @Autowired
    PaymentOutboxRepository paymentOutboxRepository;

    @Autowired
    IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    PaymentStatusHistoryRepository paymentStatusHistoryRepository;

    @Autowired
    RefundRepository refundRepository;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PaymentTestContainers.register(registry);
        registry.add("sentinelpay.providers.stripe.mode", () -> "real");
        registry.add("sentinelpay.providers.stripe.api-key", () -> "sk_test_wiremock");
        registry.add("sentinelpay.providers.stripe.base-url", wireMock::baseUrl);
        registry.add("sentinelpay.routing.policy", () -> "static");
        registry.add("sentinelpay.routing.breaker.enabled", () -> "false");
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
        wireMock.resetAll();
        stubStripeAuthorizeCaptureSuccess();
    }

    @Test
    void hardFailFailover_completesOnStripeProvider() {
        providerBehavior.program(Provider.MOCKPAY, Outcome.HARD_FAIL);

        ChargeResult result = chargeService.charge(command("stripe-real-failover"));

        assertThat(result.status()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(result.provider()).isEqualTo(Provider.STRIPE);
        assertThat(mockPayProvider.authorizationCount()).isZero();
    }

    /**
     * The Phase 11 regression guard. MockPay hard-fails, so Stripe is attempted and returns
     * {@code processing} (ambiguous). Reconcile replays the create and Stripe reports the original
     * intent as {@code requires_capture} — i.e. the authorization DID succeed. The charge must
     * complete on that same intent with no further authorization attempt.
     *
     * <p>Under the old code this double-charged: reconcile replayed with {@code amount=0}, Stripe
     * raised an idempotency error, it mapped to HARD_FAIL, and ChargeService failed onward while the
     * Stripe authorization was live.
     */
    @Test
    void stripeAmbiguousThenReconciledAuthorized_completesWithoutDoubleCharge() {
        wireMock.resetAll();
        providerBehavior.program(Provider.MOCKPAY, Outcome.HARD_FAIL);

        // 1st create (authorize) -> processing = ambiguous; 2nd create (reconcile replay) -> authorized.
        wireMock.stubFor(post(urlEqualTo("/v1/payment_intents"))
                .inScenario("stripe-ambiguous")
                .whenScenarioStateIs(STARTED)
                .willSetStateTo("reconcile")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"id":"pi_ambig","object":"payment_intent","status":"processing"}
                                """)));
        wireMock.stubFor(post(urlEqualTo("/v1/payment_intents"))
                .inScenario("stripe-ambiguous")
                .whenScenarioStateIs("reconcile")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"id":"pi_ambig","object":"payment_intent","status":"requires_capture"}
                                """)));
        wireMock.stubFor(post(urlPathMatching("/v1/payment_intents/.+/capture"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"id":"pi_ambig","object":"payment_intent","status":"succeeded"}
                                """)));

        ChargeResult result = chargeService.charge(command("stripe-real-ambig-reconciled"));

        assertThat(result.status()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(result.provider()).isEqualTo(Provider.STRIPE);
        // Exactly two creates: the ambiguous authorize + the reconcile replay. A third would mean a
        // re-authorization after reconcile — the double-charge this phase exists to prevent.
        wireMock.verify(2, postRequestedFor(urlEqualTo("/v1/payment_intents")));
        // Reconcile must replay the ORIGINAL amount, never a degraded amount=0 probe.
        wireMock.verify(2, postRequestedFor(urlEqualTo("/v1/payment_intents"))
                .withRequestBody(matching("(?s).*amount=2500.*")));
    }

    @Test
    void ambiguousAuthorized_reconcilesOnMockPayWithoutStripeAttempt() {
        wireMock.resetAll();
        providerBehavior.program(Provider.MOCKPAY, Outcome.AMBIGUOUS_TIMEOUT);

        ChargeResult result = chargeService.charge(command("stripe-real-ambig-auth"));

        assertThat(result.status()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(result.provider()).isEqualTo(Provider.MOCKPAY);
        assertThat(paymentAttemptRepository.findAll()).hasSize(1);
    }

    @Test
    void ambiguousNotAuthorized_failoverToStripeProvider() {
        providerBehavior.program(
                Provider.MOCKPAY, ProviderBehavior.ProgrammedOutcome.ambiguousWithoutStore());

        ChargeResult result = chargeService.charge(command("stripe-real-ambig-no-auth"));

        assertThat(result.status()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(result.provider()).isEqualTo(Provider.STRIPE);
        assertThat(mockPayProvider.authorizationCount()).isZero();
    }

    private void stubStripeAuthorizeCaptureSuccess() {
        wireMock.stubFor(post(urlEqualTo("/v1/payment_intents"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "id": "pi_wiremock_ok",
                                  "object": "payment_intent",
                                  "status": "requires_capture"
                                }
                                """)));
        wireMock.stubFor(post(urlPathMatching("/v1/payment_intents/.+/capture"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "id": "pi_wiremock_ok",
                                  "object": "payment_intent",
                                  "status": "succeeded"
                                }
                                """)));
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
