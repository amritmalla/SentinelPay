package com.sentinelpay.payment.application;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentEventPayloadsTest {

    @Test
    void paymentCompleted_containsRequiredFields() {
        UUID merchantId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();

        Map<String, Object> payload = PaymentEventPayloads.paymentCompleted(
                merchantId,
                "corr-1",
                paymentId,
                "buyer@example.com",
                2500,
                "mockpay");

        assertThat(payload).containsEntry("merchantId", merchantId.toString());
        assertThat(payload).containsEntry("correlationId", "corr-1");
        assertThat(payload).containsEntry("paymentId", paymentId.toString());
        assertThat(payload).containsEntry("customerEmail", "buyer@example.com");
        assertThat(payload).containsEntry("amountCents", 2500L);
        assertThat(payload).containsEntry("provider", "mockpay");
    }
}
