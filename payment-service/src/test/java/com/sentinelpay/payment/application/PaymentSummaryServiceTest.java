package com.sentinelpay.payment.application;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentSummaryServiceTest {

    @Test
    void scoreBand_fromScore_usesRiskThresholds() {
        assertThat(PaymentSummaryService.scoreBand(new BigDecimal("0.10"), null)).isEqualTo("LOW");
        assertThat(PaymentSummaryService.scoreBand(new BigDecimal("0.30"), null)).isEqualTo("MEDIUM");
        assertThat(PaymentSummaryService.scoreBand(new BigDecimal("0.70"), null)).isEqualTo("HIGH");
    }

    @Test
    void scoreBand_withoutScore_usesRecommendation() {
        assertThat(PaymentSummaryService.scoreBand(null, "APPROVE")).isEqualTo("LOW");
        assertThat(PaymentSummaryService.scoreBand(null, "REVIEW")).isEqualTo("MEDIUM");
        assertThat(PaymentSummaryService.scoreBand(null, "BLOCK")).isEqualTo("HIGH");
    }
}
