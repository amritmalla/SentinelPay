package com.sentinelpay.risk.domain.scoring;

public interface RiskScorer {

    RiskResult score(ScoringInput input);
}
