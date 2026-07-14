package com.sentinelpay.payment.infrastructure.risk;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.sentinelpay.common.security.GatewayHeaders;
import com.sentinelpay.common.security.GatewaySecurityProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.web.client.RestClient;

import java.util.Optional;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

class RiskAssessmentClientTest {

    private static final String GATEWAY_SECRET = "test-gateway-secret";

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private RiskAssessmentClient client;

    @BeforeEach
    void setUp() {
        GatewaySecurityProperties properties = new GatewaySecurityProperties();
        properties.setGatewaySecret(GATEWAY_SECRET);
        client = new RiskAssessmentClient(RestClient.builder(), wireMock.baseUrl(), properties);
    }

    @Test
    void fetch_sendsGatewayIdentityHeadersAndReturnsAssessment() {
        UUID transactionId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        String body = """
                {
                  "transaction_id": "%s",
                  "score": 0.1,
                  "recommendation": "APPROVE",
                  "contributing_factors": ["no_risk_signals"],
                  "model_version": "rules-v1.0.0",
                  "fallback_used": false
                }
                """.formatted(transactionId);

        wireMock.stubFor(get("/api/v1/fraud-assessments/" + transactionId)
                .withHeader(GatewayHeaders.GATEWAY_SECRET, equalTo(GATEWAY_SECRET))
                .withHeader(GatewayHeaders.AUTH_ROLE, equalTo("OPS"))
                .withHeader(GatewayHeaders.MERCHANT_ID, equalTo(merchantId.toString()))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));

        Optional<RiskTrailView> result = client.fetch(transactionId, merchantId);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().recommendation()).isEqualTo("APPROVE");
        assertThat(result.orElseThrow().contributingFactors()).containsExactly("no_risk_signals");
        assertThat(result.orElseThrow().modelVersion()).isEqualTo("rules-v1.0.0");
        wireMock.verify(getRequestedFor(urlEqualTo("/api/v1/fraud-assessments/" + transactionId)));
    }

    @Test
    void fetch_withoutGatewaySecret_returnsEmpty() {
        UUID transactionId = UUID.randomUUID();
        wireMock.stubFor(get("/api/v1/fraud-assessments/" + transactionId)
                .willReturn(aResponse().withStatus(401)));

        Optional<RiskTrailView> result = client.fetch(transactionId, UUID.randomUUID());

        assertThat(result).isEmpty();
    }

    @Test
    void fetch_notFound_returnsEmpty() {
        UUID transactionId = UUID.randomUUID();
        wireMock.stubFor(get("/api/v1/fraud-assessments/" + transactionId)
                .willReturn(aResponse().withStatus(404)));

        Optional<RiskTrailView> result = client.fetch(transactionId, UUID.randomUUID());

        assertThat(result).isEmpty();
    }
}
