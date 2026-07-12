package com.sentinelpay.payment.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentStateMachineTest {

    @ParameterizedTest
    @CsvSource({
            "CREATED,RISK_EVALUATED",
            "CREATED,FAILED",
            "RISK_EVALUATED,BLOCKED",
            "RISK_EVALUATED,IN_REVIEW",
            "RISK_EVALUATED,AUTHORIZING",
            "AUTHORIZING,AUTHORIZED",
            "AUTHORIZING,FAILED",
            "AUTHORIZED,CAPTURED",
            "AUTHORIZED,FAILED",
            "CAPTURED,COMPLETED",
            "COMPLETED,REFUND_PENDING",
            "REFUND_PENDING,REFUNDED",
            "REFUND_PENDING,FAILED"
    })
    void assertTransition_allowsLegalTransitions(PaymentStatus from, PaymentStatus to) {
        PaymentStateMachine.assertTransition(from, to);
    }

    @ParameterizedTest
    @CsvSource({
            "CREATED,COMPLETED",
            "AUTHORIZING,CAPTURED",
            "BLOCKED,COMPLETED",
            "COMPLETED,FAILED"
    })
    void assertTransition_rejectsIllegalTransitions(PaymentStatus from, PaymentStatus to) {
        assertThatThrownBy(() -> PaymentStateMachine.assertTransition(from, to))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void isTerminal_recognizesTerminalStatuses() {
        assertThat(PaymentStateMachine.isTerminal(PaymentStatus.COMPLETED)).isFalse();
        assertThat(PaymentStateMachine.isTerminal(PaymentStatus.FAILED)).isTrue();
        assertThat(PaymentStateMachine.isTerminal(PaymentStatus.BLOCKED)).isTrue();
        assertThat(PaymentStateMachine.isTerminal(PaymentStatus.REFUNDED)).isTrue();
        assertThat(PaymentStateMachine.isTerminal(PaymentStatus.AUTHORIZING)).isFalse();
    }
}
