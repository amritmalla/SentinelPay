package com.sentinelpay.payment.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelpay.common.error.ApiException;
import com.sentinelpay.common.error.ErrorCode;
import com.sentinelpay.payment.domain.PaymentStatus;
import com.sentinelpay.payment.domain.Provider;
import com.sentinelpay.payment.infrastructure.persistence.PaymentAttemptEntity;
import com.sentinelpay.payment.infrastructure.persistence.PaymentAttemptRepository;
import com.sentinelpay.payment.infrastructure.persistence.PaymentEntity;
import com.sentinelpay.payment.infrastructure.persistence.PaymentRepository;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.net.Webhook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Service
public class WebhookService {

    private final PaymentAttemptRepository paymentAttemptRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentTransactionService paymentTransactionService;
    private final WebhookDedupService webhookDedupService;
    private final ObjectMapper objectMapper;
    private final String webhookSecret;

    public WebhookService(
            PaymentAttemptRepository paymentAttemptRepository,
            PaymentRepository paymentRepository,
            PaymentTransactionService paymentTransactionService,
            WebhookDedupService webhookDedupService,
            ObjectMapper objectMapper,
            @Value("${sentinelpay.providers.stripe.webhook-secret}") String webhookSecret) {
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.paymentRepository = paymentRepository;
        this.paymentTransactionService = paymentTransactionService;
        this.webhookDedupService = webhookDedupService;
        this.objectMapper = objectMapper;
        this.webhookSecret = webhookSecret;
    }

    public void handle(String rawBody, String signature) {
        Event stripeEvent;
        try {
            stripeEvent = Webhook.constructEvent(rawBody, signature, webhookSecret);
        } catch (SignatureVerificationException ex) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "invalid_webhook_signature");
        }

        JsonNode event;
        try {
            event = objectMapper.readTree(rawBody);
        } catch (Exception ex) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "invalid_webhook_event");
        }

        applyEvent(event, stripeEvent.getId(), stripeEvent.getType());
    }

    private void applyEvent(JsonNode event, String eventId, String eventType) {
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
