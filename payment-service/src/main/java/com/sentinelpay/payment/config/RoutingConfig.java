package com.sentinelpay.payment.config;

import com.sentinelpay.payment.application.routing.BanditRankingPolicy;
import com.sentinelpay.payment.application.routing.RankingPolicy;
import com.sentinelpay.payment.application.routing.ScoredRankingPolicy;
import com.sentinelpay.payment.application.routing.StaticRankingPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@EnableConfigurationProperties(RoutingProperties.class)
public class RoutingConfig {

    @Bean
    StaticRankingPolicy staticRankingPolicy() {
        return new StaticRankingPolicy();
    }

    @Bean
    ScoredRankingPolicy scoredRankingPolicy() {
        return new ScoredRankingPolicy();
    }

    @Bean
    List<RankingPolicy> rankingPolicies(
            StaticRankingPolicy staticPolicy,
            ScoredRankingPolicy scoredPolicy,
            BanditRankingPolicy banditPolicy) {
        return List.of(staticPolicy, scoredPolicy, banditPolicy);
    }
}
