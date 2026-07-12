package com.sentinelpay.risk.domain.scoring;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AmountRuleTest {

    private final AmountRule rule = new AmountRule();

    @Test
    void evaluate_lowAmount_doesNotFire() {
        RuleResult result = rule.evaluate(input(2_500));

        assertThat(result.weight()).isZero();
        assertThat(result.factor()).isNull();
    }

    @Test
    void evaluate_midAmount_firesAtTwentyCents() {
        RuleResult result = rule.evaluate(input(50_000));

        assertThat(result.weight()).isEqualTo(0.20);
        assertThat(result.factor()).isEqualTo("amount:50000");
    }

    @Test
    void evaluate_highAmount_firesAtFortyFiveCents() {
        RuleResult result = rule.evaluate(input(100_000));

        assertThat(result.weight()).isEqualTo(0.45);
        assertThat(result.factor()).isEqualTo("amount:100000");
    }

    private static ScoringInput input(long amountCents) {
        return new ScoringInput(UUID.randomUUID(), UUID.randomUUID(), amountCents, "USD", "a@b.com", 0);
    }
}
