package com.sentinelpay.payment.infrastructure.risk;

import com.sentinelpay.common.security.GatewayHeaders;
import com.sentinelpay.common.security.GatewaySecurityProperties;
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
    private static final String OPS_ROLE = "OPS";

    private final RestClient restClient;
    private final String gatewaySecret;

    public RiskAssessmentClient(
            RestClient.Builder restClientBuilder,
            @Value("${sentinelpay.risk.http.base-url:http://localhost:8083}") String baseUrl,
            GatewaySecurityProperties securityProperties) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        this.gatewaySecret = securityProperties.getGatewaySecret();
    }

    /**
     * Fetches the persisted assessment for trail composition. Internal service-to-service call:
     * {@code OPS} satisfies fraud-assessment authz; the payment's merchant id carries tenant context
     * (not merchant impersonation — lookup is by transaction id).
     */
    public Optional<RiskTrailView> fetch(UUID transactionId, UUID merchantId) {
        try {
            RiskTrailView view = restClient.get()
                    .uri("/api/v1/fraud-assessments/{transactionId}", transactionId)
                    .header(GatewayHeaders.GATEWAY_SECRET, gatewaySecret)
                    .header(GatewayHeaders.AUTH_ROLE, OPS_ROLE)
                    .header(GatewayHeaders.MERCHANT_ID, merchantId.toString())
                    .retrieve()
                    .body(RiskTrailView.class);
            return Optional.ofNullable(view);
        } catch (HttpClientErrorException.NotFound ex) {
            return Optional.empty();
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden ex) {
            log.warn("Risk assessment fetch denied for {}: {}", transactionId, ex.getStatusCode());
            return Optional.empty();
        } catch (RestClientException ex) {
            log.warn("Risk assessment fetch failed for {}: {}", transactionId, ex.getMessage());
            return Optional.empty();
        }
    }
}
