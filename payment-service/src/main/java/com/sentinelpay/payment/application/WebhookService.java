package com.sentinelpay.payment.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.sentinelpay.common.error.ApiException;
import com.sentinelpay.common.error.ErrorCode;
import com.sentinelpay.payment.domain.PaymentStatus;
import com.sentinelpay.payment.domain.Provider;
import com.sentinelpay.payment.infrastructure.persistence.PaymentAttemptEntity;
import com.sentinelpay.payment.infrastructure.persistence.PaymentAttemptRepository;
import com.sentinelpay.payment.infrastructure.persistence.PaymentEntity;
import com.sentinelpay.payment.infrastructure.persistence.PaymentRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Service
public class WebhookService {

    private final PaymentAttemptRepository paymentAttemptRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentTransactionService paymentTransactionService;
    private final WebhookDedupService webhookDedupService;

    public WebhookService(
            PaymentAttemptRepository paymentAttemptRepository,
            PaymentRepository paymentRepository,
            PaymentTransactionService paymentTransactionService,
            WebhookDedupService webhookDedupService) {
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.paymentRepository = paymentRepository;
        this.paymentTransactionService = paymentTransactionService;
        this.webhookDedupService = webhookDedupService;
    }

    public void handle(JsonNode event) {
        String eventId = textOrNull(event, "id");
        String eventType = textOrNull(event, "type");
        if (eventId == null || eventType == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "invalid_webhook_event");
        }

        if (!webhookDedupService.recordIfNew(eventId, eventType)) {
            return;
        }

        String objectId = textOrNull(event.at("/data/object"), "id");
        if (objectId == null) {
            return;
        }

        Optional<PaymentAttemptEntity> attempt = paymentAttemptRepository.findByProviderRef(objectId);
        if (attempt.isEmpty()) {
            return;
        }

        UUID paymentId = attempt.get().getPaymentId();
        PaymentEntity payment = paymentRepository.findById(paymentId).orElse(null);
        if (payment == null) {
            return;
        }

        switch (eventType) {
            case "payment_intent.succeeded", "charge.succeeded" -> {
                if (payment.getStatus() == PaymentStatus.AUTHORIZING) {
                    Provider provider = Provider.fromDbValue(attempt.get().getProvider());
                    paymentTransactionService.completeFromWebhook(
                            paymentId,
                            provider,
                            objectId,
                            attempt.get().getAttemptNumber());
                }
            }
            case "charge.refunded" -> paymentTransactionService.markRefundedFromWebhook(paymentId);
            default -> {
                // acknowledged, no state change
            }
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text.isBlank() ? null : text;
    }
}
