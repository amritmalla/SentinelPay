package com.sentinelpay.risk.infrastructure.scoring;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sentinelpay.risk.domain.scoring.RiskResult;
import com.sentinelpay.risk.domain.scoring.RiskScorer;
import com.sentinelpay.risk.domain.scoring.RuleBasedScorer;
import com.sentinelpay.risk.domain.scoring.ScoringInput;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP adapter to the Python risk-model service. On any failure, falls back to
 * the in-process {@link RuleBasedScorer} so the charge path never fails open.
 */
public final class MlScorer implements RiskScorer {

    private static final Logger log = LoggerFactory.getLogger(MlScorer.class);
    private static final String FALLBACK_FACTOR = "ml_unavailable_rules_fallback";

    private final RestClient restClient;
    private final RuleBasedScorer fallback;
    private final double blockThreshold;
    private final double reviewThreshold;
    private final Counter fallbackCounter;

    public MlScorer(
            RestClient restClient,
            RuleBasedScorer fallback,
            double blockThreshold,
            double reviewThreshold,
            MeterRegistry meterRegistry) {
        this.restClient = restClient;
        this.fallback = fallback;
        this.blockThreshold = blockThreshold;
        this.reviewThreshold = reviewThreshold;
        this.fallbackCounter = Counter.builder("sentinelpay_ml_scorer_fallback_total")
                .description("ML scorer failures that fell back to rule-based scoring")
                .register(meterRegistry);
    }

    @Override
    public RiskResult score(ScoringInput input) {
        try {
            MlScoreResponse response = restClient.post()
                    .uri("/score")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(toRequest(input))
                    .retrieve()
                    .body(MlScoreResponse.class);
            if (response == null || response.score() == null) {
                return fallbackWithFactor(input, "null response");
            }
            double score = clamp(response.score());
            String recommendation = score >= blockThreshold ? "BLOCK"
                    : score >= reviewThreshold ? "REVIEW"
                    : "APPROVE";
            List<String> factors = response.contributingFactors() == null || response.contributingFactors().isEmpty()
                    ? List.of("ml_no_strong_positive_factors")
                    : List.copyOf(response.contributingFactors());
            String modelVersion = response.modelVersion() == null || response.modelVersion().isBlank()
                    ? "ml-unknown"
                    : response.modelVersion();
            return new RiskResult(score, recommendation, factors, modelVersion);
        } catch (RestClientException | IllegalStateException ex) {
            return fallbackWithFactor(input, ex.toString());
        }
    }

    private RiskResult fallbackWithFactor(ScoringInput input, String reason) {
        log.warn("ML scorer unavailable, using rules fallback: {}", reason);
        fallbackCounter.increment();
        RiskResult rules = fallback.score(input);
        List<String> factors = new ArrayList<>(rules.factors());
        factors.add(FALLBACK_FACTOR);
        return new RiskResult(rules.score(), rules.recommendation(), factors, rules.modelVersion());
    }

    private static Map<String, Object> toRequest(ScoringInput input) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount_cents", input.amountCents());
        body.put("currency", input.currency() == null ? "USD" : input.currency());
        body.put("customer_email", input.customerEmail() == null ? "" : input.customerEmail());
        body.put("velocity_last_hour", input.velocityLastHour());
        if (input.merchantCategory() != null && !input.merchantCategory().isBlank()) {
            body.put("merchant_category", input.merchantCategory());
        }
        if (input.cardCountry() != null && !input.cardCountry().isBlank()) {
            body.put("card_country", input.cardCountry());
        }
        if (input.merchantCountry() != null && !input.merchantCountry().isBlank()) {
            body.put("merchant_country", input.merchantCountry());
        }
        if (input.timestampEpochMs() != null) {
            body.put("timestamp_epoch_ms", input.timestampEpochMs());
        }
        return body;
    }

    private static double clamp(double score) {
        if (Double.isNaN(score) || score < 0) {
            return 0;
        }
        return Math.min(1.0, score);
    }

    public record MlScoreResponse(
            Double score,
            @JsonProperty("contributing_factors") List<String> contributingFactors,
            @JsonProperty("model_version") String modelVersion) {
    }
}
