package com.sentinelpay.risk.domain.scoring;

public final class VelocityRule implements Rule {

    @Override
    public RuleResult evaluate(ScoringInput input) {
        long velocity = input.velocityLastHour();
        double weight = velocity >= 10 ? 0.50 : velocity >= 5 ? 0.25 : 0.0;
        return new RuleResult(weight, weight > 0 ? "velocity_1h:" + velocity : null);
    }
}
