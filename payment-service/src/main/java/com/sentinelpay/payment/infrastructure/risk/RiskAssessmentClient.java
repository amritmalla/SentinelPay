package com.sentinelpay.payment.infrastructure.risk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Optional;
import java.util.UUID;

@Component
public class RiskAssessmentClient {

    private static final Logger log = LoggerFactory.getLogger(RiskAssessmentClient.class);

    private final RestClient restClient;

    public RiskAssessmentClient(
            RestClient.Builder restClientBuilder,
            @Value("${sentinelpay.risk.http.base-url:http://localhost:8083}") String baseUrl) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
    }

    public Optional<RiskTrailView> fetch(UUID transactionId) {
        try {
            RiskTrailView view = restClient.get()
                    .uri("/api/v1/fraud-assessments/{transactionId}", transactionId)
                    .retrieve()
                    .body(RiskTrailView.class);
            return Optional.ofNullable(view);
        } catch (HttpClientErrorException.NotFound ex) {
            return Optional.empty();
        } catch (RestClientException ex) {
            log.warn("Risk assessment fetch failed for {}: {}", transactionId, ex.getMessage());
            return Optional.empty();
        }
    }
}
