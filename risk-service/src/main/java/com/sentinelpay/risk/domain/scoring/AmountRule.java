package com.sentinelpay.risk.domain.scoring;

public final class AmountRule implements Rule {

    @Override
    public RuleResult evaluate(ScoringInput input) {
        double weight = input.amountCents() >= 100_000 ? 0.45
                : input.amountCents() >= 50_000 ? 0.20
                : 0.0;
        return new RuleResult(weight, weight > 0 ? "amount:" + input.amountCents() : null);
    }
}
