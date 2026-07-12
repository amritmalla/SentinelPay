package com.sentinelpay.payment.application;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class PaymentEventPayloads {

    private PaymentEventPayloads() {
    }

    public static Map<String, Object> paymentCompleted(
            UUID merchantId,
            String correlationId,
            UUID paymentId,
            String customerEmail,
            long amountCents,
            String provider) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("merchantId", merchantId.toString());
        payload.put("correlationId", correlationId);
        payload.put("paymentId", paymentId.toString());
        payload.put("customerEmail", customerEmail);
        payload.put("amountCents", amountCents);
        payload.put("provider", provider);
        return payload;
    }

    public static Map<String, Object> paymentFailed(
            UUID merchantId,
            String correlationId,
            UUID paymentId,
            String customerEmail,
            String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("merchantId", merchantId.toString());
        payload.put("correlationId", correlationId);
        payload.put("paymentId", paymentId.toString());
        payload.put("customerEmail", customerEmail);
        payload.put("reason", reason);
        return payload;
    }

    public static Map<String, Object> paymentRefunded(
            UUID merchantId,
            String correlationId,
            UUID paymentId,
            UUID refundId,
            long amountCents) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("merchantId", merchantId.toString());
        payload.put("correlationId", correlationId);
        payload.put("paymentId", paymentId.toString());
        payload.put("refundId", refundId.toString());
        payload.put("amountCents", amountCents);
        return payload;
    }
}
