package com.sentinelpay.risk.domain.scoring;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DisposableEmailRuleTest {

    private final DisposableEmailRule rule = new DisposableEmailRule();

    @Test
    void evaluate_normalEmail_doesNotFire() {
        RuleResult result = rule.evaluate(input("buyer@example.com"));

        assertThat(result.weight()).isZero();
        assertThat(result.factor()).isNull();
    }

    @Test
    void evaluate_disposableDomain_firesAtFortyCents() {
        RuleResult result = rule.evaluate(input("user@GuerrillaMail.com"));

        assertThat(result.weight()).isEqualTo(0.40);
        assertThat(result.factor()).isEqualTo("disposable_email:guerrillamail.com");
    }

    @Test
    void evaluate_nullEmail_doesNotFire() {
        RuleResult result = rule.evaluate(input(null));

        assertThat(result.weight()).isZero();
        assertThat(result.factor()).isNull();
    }

    private static ScoringInput input(String email) {
        return new ScoringInput(UUID.randomUUID(), UUID.randomUUID(), 1000, "USD", email, 0);
    }
}
