package com.sentinelpay.risk.domain.scoring;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedScorerTest {

    private RuleBasedScorer scorer;

    @BeforeEach
    void setUp() {
        scorer = new RuleBasedScorer(
                List.of(new AmountRule(), new VelocityRule(), new DisposableEmailRule()), 0.70, 0.30);
    }

    @Test
    void score_lowRiskInput_approvesWithNoRiskSignals() {
        RiskResult result = scorer.score(input(2_500, 0, "buyer@example.com"));

        assertThat(result.recommendation()).isEqualTo("APPROVE");
        assertThat(result.score()).isZero();
        assertThat(result.factors()).containsExactly("no_risk_signals");
        assertThat(result.modelVersion()).isEqualTo("rules-v1.0.0");
    }

    @Test
    void score_amountAndVelocity_reviews() {
        RiskResult result = scorer.score(input(60_000, 6, "buyer@example.com"));

        assertThat(result.score()).isEqualTo(0.45, org.assertj.core.data.Offset.offset(0.001));
        assertThat(result.recommendation()).isEqualTo("REVIEW");
        assertThat(result.factors()).containsExactly("amount:60000", "velocity_1h:6");
    }

    @Test
    void score_highAmountAndDisposableEmail_blocks() {
        RiskResult result = scorer.score(input(120_000, 0, "user@mailinator.com"));

        assertThat(result.score()).isEqualTo(0.85, org.assertj.core.data.Offset.offset(0.001));
        assertThat(result.recommendation()).isEqualTo("BLOCK");
        assertThat(result.factors()).containsExactly("amount:120000", "disposable_email:mailinator.com");
    }

    @Test
    void score_manyRulesFiring_clampsAtOne() {
        RiskResult result = scorer.score(input(120_000, 10, "user@mailinator.com"));

        assertThat(result.score()).isEqualTo(1.0);
        assertThat(result.recommendation()).isEqualTo("BLOCK");
        assertThat(result.factors()).hasSize(3);
    }

    private static ScoringInput input(long amountCents, long velocity, String email) {
        return new ScoringInput(UUID.randomUUID(), UUID.randomUUID(), amountCents, "USD", email, velocity);
    }
}
