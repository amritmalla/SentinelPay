package com.sentinelpay.risk.domain.scoring;

import java.util.ArrayList;
import java.util.List;

public final class RuleBasedScorer implements RiskScorer {

    private static final String MODEL_VERSION = "rules-v1.0.0";

    private final List<Rule> rules;
    private final double blockThreshold;
    private final double reviewThreshold;

    public RuleBasedScorer(List<Rule> rules, double blockThreshold, double reviewThreshold) {
        this.rules = List.copyOf(rules);
        this.blockThreshold = blockThreshold;
        this.reviewThreshold = reviewThreshold;
    }

    @Override
    public RiskResult score(ScoringInput input) {
        double score = 0;
        List<String> factors = new ArrayList<>();
        for (Rule rule : rules) {
            RuleResult result = rule.evaluate(input);
            score += result.weight();
            if (result.factor() != null) {
                factors.add(result.factor());
            }
        }
        score = Math.min(1.0, score);
        String recommendation = score >= blockThreshold ? "BLOCK"
                : score >= reviewThreshold ? "REVIEW"
                : "APPROVE";
        if (factors.isEmpty()) {
            factors.add("no_risk_signals");
        }
        return new RiskResult(score, recommendation, factors, MODEL_VERSION);
    }
}
