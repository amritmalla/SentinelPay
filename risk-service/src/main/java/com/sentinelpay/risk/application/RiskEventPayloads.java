package com.sentinelpay.risk.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class RiskEventPayloads {

    private RiskEventPayloads() {
    }

    public static Map<String, Object> assessed(
            UUID transactionId,
            UUID merchantId,
            double score,
            String recommendation,
            List<String> factors,
            String modelVersion) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("transactionId", transactionId.toString());
        payload.put("merchantId", merchantId.toString());
        payload.put("score", score);
        payload.put("recommendation", recommendation);
        payload.put("factors", factors);
        payload.put("modelVersion", modelVersion);
        payload.put("correlationId", transactionId.toString());
        return payload;
    }

    public static Map<String, Object> highAlert(
            UUID merchantId, UUID transactionId, double score, List<String> factors) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("merchantId", merchantId.toString());
        payload.put("transactionId", transactionId.toString());
        payload.put("score", score);
        payload.put("factors", factors);
        return payload;
    }
}
