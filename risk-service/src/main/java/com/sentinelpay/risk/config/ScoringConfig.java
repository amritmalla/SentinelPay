package com.sentinelpay.risk.config;

import com.sentinelpay.risk.domain.scoring.AmountRule;
import com.sentinelpay.risk.domain.scoring.DisposableEmailRule;
import com.sentinelpay.risk.domain.scoring.RiskScorer;
import com.sentinelpay.risk.domain.scoring.RuleBasedScorer;
import com.sentinelpay.risk.domain.scoring.VelocityRule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class ScoringConfig {

    @Bean
    RiskScorer riskScorer(
            @Value("${sentinelpay.risk.scoring.block-threshold:0.70}") double blockThreshold,
            @Value("${sentinelpay.risk.scoring.review-threshold:0.30}") double reviewThreshold) {
        return new RuleBasedScorer(
                List.of(new AmountRule(), new VelocityRule(), new DisposableEmailRule()),
                blockThreshold,
                reviewThreshold);
    }
}
