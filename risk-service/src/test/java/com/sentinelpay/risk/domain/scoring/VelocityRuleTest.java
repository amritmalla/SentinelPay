package com.sentinelpay.risk.domain.scoring;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class VelocityRuleTest {

    private final VelocityRule rule = new VelocityRule();

    @Test
    void evaluate_noVelocity_doesNotFire() {
        RuleResult result = rule.evaluate(input(4));

        assertThat(result.weight()).isZero();
        assertThat(result.factor()).isNull();
    }

    @Test
    void evaluate_moderateVelocity_firesAtTwentyFiveCents() {
        RuleResult result = rule.evaluate(input(5));

        assertThat(result.weight()).isEqualTo(0.25);
        assertThat(result.factor()).isEqualTo("velocity_1h:5");
    }

    @Test
    void evaluate_highVelocity_firesAtFiftyCents() {
        RuleResult result = rule.evaluate(input(10));

        assertThat(result.weight()).isEqualTo(0.50);
        assertThat(result.factor()).isEqualTo("velocity_1h:10");
    }

    private static ScoringInput input(long velocity) {
        return new ScoringInput(UUID.randomUUID(), UUID.randomUUID(), 1000, "USD", "a@b.com", velocity);
    }
}
