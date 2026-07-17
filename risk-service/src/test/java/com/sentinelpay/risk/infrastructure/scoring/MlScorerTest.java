package com.sentinelpay.risk.infrastructure.scoring;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.sentinelpay.risk.domain.scoring.AmountRule;
import com.sentinelpay.risk.domain.scoring.DisposableEmailRule;
import com.sentinelpay.risk.domain.scoring.RiskResult;
import com.sentinelpay.risk.domain.scoring.RuleBasedScorer;
import com.sentinelpay.risk.domain.scoring.ScoringInput;
import com.sentinelpay.risk.domain.scoring.VelocityRule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

class MlScorerTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private MlScorer scorer;
    private SimpleMeterRegistry registry;
    private RuleBasedScorer rules;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        rules = new RuleBasedScorer(
                List.of(new AmountRule(), new VelocityRule(), new DisposableEmailRule()), 0.70, 0.30);
        // Generous timeouts: these tests exercise mapping/fallback logic, not latency. A tight
        // budget here flakes under full-reactor load (WireMock's first cold-JVM request can
        // exceed 200 ms). The timeout path gets its own tight-budget scorer in its test.
        scorer = new MlScorer(client(Duration.ofSeconds(5)), rules, 0.70, 0.30, registry);
    }

    private static RestClient client(Duration timeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        return RestClient.builder()
                .baseUrl(wireMock.baseUrl())
                .requestFactory(factory)
                .build();
    }

    @Test
    void score_happyPath_mapsScoreFactorsAndAppliesJavaThresholds() {
        wireMock.stubFor(post("/score").willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {
                          "score": 0.82,
                          "contributing_factors": ["ml_velocity_last_hour_high", "ml_geo_mismatch"],
                          "model_version": "ml-v1.0.0+deadbeef"
                        }
                        """)));

        RiskResult result = scorer.score(input(2_500, 8, "buyer@example.com", "GB", "US"));

        assertThat(result.score()).isEqualTo(0.82);
        assertThat(result.recommendation()).isEqualTo("BLOCK");
        assertThat(result.factors()).containsExactly("ml_velocity_last_hour_high", "ml_geo_mismatch");
        assertThat(result.modelVersion()).isEqualTo("ml-v1.0.0+deadbeef");
        assertThat(registry.get("sentinelpay_ml_scorer_fallback_total").counter().count()).isZero();
        wireMock.verify(postRequestedFor(urlEqualTo("/score"))
                .withRequestBody(equalToJson("""
                        {
                          "amount_cents": 2500,
                          "currency": "USD",
                          "customer_email": "buyer@example.com",
                          "velocity_last_hour": 8,
                          "merchant_category": "retail",
                          "card_country": "GB",
                          "merchant_country": "US",
                          "timestamp_epoch_ms": 1700000000000
                        }
                        """)));
    }

    @Test
    void score_timeout_fallsBackToRules() {
        wireMock.stubFor(post("/score").willReturn(aResponse()
                .withFixedDelay(2_000)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"score\":0.9,\"contributing_factors\":[\"x\"],\"model_version\":\"ml-v1\"}")));

        MlScorer tightScorer = new MlScorer(client(Duration.ofMillis(200)), rules, 0.70, 0.30, registry);
        RiskResult result = tightScorer.score(input(120_000, 0, "user@mailinator.com", null, null));

        assertThat(result.modelVersion()).isEqualTo("rules-v1.0.0");
        assertThat(result.factors()).contains("ml_unavailable_rules_fallback");
        assertThat(result.recommendation()).isEqualTo("BLOCK");
        assertThat(registry.get("sentinelpay_ml_scorer_fallback_total").counter().count()).isEqualTo(1.0);
    }

    @Test
    void score_malformedResponse_fallsBackToRules() {
        wireMock.stubFor(post("/score").willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"score\":\"not-a-number\"}")));

        RiskResult result = scorer.score(input(2_500, 0, "buyer@example.com", null, null));

        assertThat(result.modelVersion()).isEqualTo("rules-v1.0.0");
        assertThat(result.factors()).contains("ml_unavailable_rules_fallback");
        assertThat(registry.get("sentinelpay_ml_scorer_fallback_total").counter().count()).isEqualTo(1.0);
    }

    private static ScoringInput input(
            long amountCents, long velocity, String email, String cardCountry, String merchantCountry) {
        return new ScoringInput(
                UUID.randomUUID(),
                UUID.randomUUID(),
                amountCents,
                "USD",
                email,
                velocity,
                "retail",
                cardCountry,
                merchantCountry,
                1_700_000_000_000L);
    }
}
