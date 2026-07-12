package com.sentinelpay.risk.domain.scoring;

import java.util.List;

public record RiskResult(double score, String recommendation, List<String> factors, String modelVersion) {
}
