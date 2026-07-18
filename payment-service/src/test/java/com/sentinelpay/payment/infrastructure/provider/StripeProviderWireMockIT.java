package com.sentinelpay.payment.infrastructure.provider;

import com.sentinelpay.payment.PaymentTestContainers;
import com.sentinelpay.payment.application.model.ProviderOutcome;
import com.sentinelpay.payment.application.model.ProviderOutcome.Outcome;
import com.sentinelpay.payment.application.port.PaymentProvider;
import com.sentinelpay.payment.domain.Provider;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
class StripeProviderWireMockIT {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @Autowired
    StripeProvider stripeProvider;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("sentinelpay.providers.stripe.mode", () -> "real");
        registry.add("sentinelpay.providers.stripe.api-key", () -> "sk_test_wiremock");
        registry.add("sentinelpay.providers.stripe.base-url", wireMock::baseUrl);
        PaymentTestContainers.register(registry);
    }

    @BeforeEach
    void resetWireMock() {
        wireMock.resetAll();
    }

    @Test
    void authorize_success_returnsAuthorizedWithProviderRef() {
        String downstreamKey = "idem-" + UUID.randomUUID();
        wireMock.stubFor(post(urlEqualTo("/v1/payment_intents"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "id": "pi_test_123",
                                  "object": "payment_intent",
                                  "status": "requires_capture"
                                }
                                """)));

        ProviderOutcome outcome = stripeProvider.authorize(new PaymentProvider.AuthorizeRequest(
                UUID.randomUUID(), downstreamKey, 2_500, "USD"));

        assertThat(stripeProvider.id()).isEqualTo(Provider.STRIPE);
        assertThat(outcome.outcome()).isEqualTo(Outcome.AUTHORIZED);
        assertThat(outcome.providerRef()).isEqualTo("pi_test_123");
        wireMock.verify(postRequestedFor(urlEqualTo("/v1/payment_intents"))
                .withHeader("Idempotency-Key", equalTo(downstreamKey)));
    }

    @Test
    void capture_success_returnsCaptured() {
        wireMock.stubFor(post(urlPathEqualTo("/v1/payment_intents/pi_test_123/capture"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "id": "pi_test_123",
                                  "object": "payment_intent",
                                  "status": "succeeded"
                                }
                                """)));

        ProviderOutcome outcome = stripeProvider.capture("pi_test_123");

        assertThat(outcome.outcome()).isEqualTo(Outcome.CAPTURED);
        assertThat(outcome.providerRef()).isEqualTo("pi_test_123");
    }

    @Test
    void refund_success_returnsRefunded() {
        wireMock.stubFor(post(urlEqualTo("/v1/refunds"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "id": "re_1",
                                  "object": "refund",
                                  "status": "succeeded"
                                }
                                """)));

        ProviderOutcome outcome = stripeProvider.refund("pi_test_123", 1_000);

        assertThat(outcome.outcome()).isEqualTo(Outcome.REFUNDED);
        assertThat(outcome.providerRef()).isEqualTo("re_1");
    }

    @Test
    void authorize_cardDeclined_returnsHardFail() {
        wireMock.stubFor(post(urlEqualTo("/v1/payment_intents"))
                .willReturn(aResponse()
                        .withStatus(402)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "error": {
                                    "type": "card_error",
                                    "code": "card_declined",
                                    "message": "Your card was declined."
                                  }
                                }
                                """)));

        ProviderOutcome outcome = stripeProvider.authorize(new PaymentProvider.AuthorizeRequest(
                UUID.randomUUID(), "decline-key", 2_500, "USD"));

        assertThat(outcome.outcome()).isEqualTo(Outcome.HARD_FAIL);
    }

    @Test
    void authorize_rateLimited_returnsRetryable() {
        wireMock.stubFor(post(urlEqualTo("/v1/payment_intents"))
                .willReturn(aResponse()
                        .withStatus(429)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "error": {
                                    "type": "rate_limit_error",
                                    "message": "Too many requests"
                                  }
                                }
                                """)));

        ProviderOutcome outcome = stripeProvider.authorize(new PaymentProvider.AuthorizeRequest(
                UUID.randomUUID(), "rate-limit-key", 2_500, "USD"));

        assertThat(outcome.outcome()).isEqualTo(Outcome.RETRYABLE);
    }
}
