package com.sentinelpay.risk.domain.scoring;

public interface Rule {

    RuleResult evaluate(ScoringInput input);
}
