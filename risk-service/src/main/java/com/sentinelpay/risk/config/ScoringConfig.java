package com.sentinelpay.risk.config;

import com.sentinelpay.risk.domain.scoring.AmountRule;
import com.sentinelpay.risk.domain.scoring.DisposableEmailRule;
import com.sentinelpay.risk.domain.scoring.RiskScorer;
import com.sentinelpay.risk.domain.scoring.RuleBasedScorer;
import com.sentinelpay.risk.domain.scoring.VelocityRule;
import com.sentinelpay.risk.infrastructure.scoring.MlScorer;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

@Configuration
public class ScoringConfig {

    @Bean
    RuleBasedScorer ruleBasedScorer(
            @Value("${sentinelpay.risk.scoring.block-threshold:0.70}") double blockThreshold,
            @Value("${sentinelpay.risk.scoring.review-threshold:0.30}") double reviewThreshold) {
        return new RuleBasedScorer(
                List.of(new AmountRule(), new VelocityRule(), new DisposableEmailRule()),
                blockThreshold,
                reviewThreshold);
    }

    @Bean
    RiskScorer riskScorer(
            RuleBasedScorer ruleBasedScorer,
            MeterRegistry meterRegistry,
            @Value("${sentinelpay.risk.scorer:rules}") String scorer,
            @Value("${sentinelpay.risk.model.base-url:http://localhost:8090}") String modelBaseUrl,
            @Value("${sentinelpay.risk.model.connect-timeout-ms:100}") long connectTimeoutMs,
            @Value("${sentinelpay.risk.model.read-timeout-ms:200}") long readTimeoutMs,
            @Value("${sentinelpay.risk.scoring.block-threshold:0.70}") double blockThreshold,
            @Value("${sentinelpay.risk.scoring.review-threshold:0.30}") double reviewThreshold) {
        if (!"ml".equalsIgnoreCase(scorer)) {
            return ruleBasedScorer;
        }
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        RestClient restClient = RestClient.builder()
                .baseUrl(modelBaseUrl)
                .requestFactory(requestFactory)
                .build();
        return new MlScorer(restClient, ruleBasedScorer, blockThreshold, reviewThreshold, meterRegistry);
    }
}
