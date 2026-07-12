package com.sentinelpay.payment.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelpay.payment.application.model.ChargeCommand;
import com.sentinelpay.payment.application.model.ChargeResult;
import com.sentinelpay.payment.application.model.ProviderOutcome;
import com.sentinelpay.payment.application.port.PaymentProvider;
import com.sentinelpay.payment.application.port.RiskEvaluator;
import com.sentinelpay.payment.domain.PaymentStatus;
import com.sentinelpay.payment.domain.Provider;
import com.sentinelpay.payment.infrastructure.outbox.PaymentOutboxEntity;
import com.sentinelpay.payment.infrastructure.outbox.PaymentOutboxRepository;
import com.sentinelpay.payment.infrastructure.persistence.IdempotencyKeyEntity;
import com.sentinelpay.payment.infrastructure.persistence.IdempotencyKeyId;
import com.sentinelpay.payment.infrastructure.persistence.IdempotencyKeyRepository;
import com.sentinelpay.payment.infrastructure.persistence.PaymentAttemptEntity;
import com.sentinelpay.payment.infrastructure.persistence.PaymentAttemptRepository;
import com.sentinelpay.payment.infrastructure.persistence.PaymentEntity;
import com.sentinelpay.payment.infrastructure.persistence.PaymentRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class ChargeService {

    private static final short RESPONSE_STATUS_COMPLETE = 200;
    private static final short RESPONSE_STATUS_IN_FLIGHT = 0;

    private final PaymentRepository paymentRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final PaymentOutboxRepository paymentOutboxRepository;
    private final RiskEvaluator riskEvaluator;
    private final PaymentProvider paymentProvider;
    private final ObjectMapper objectMapper;

    public ChargeService(
            PaymentRepository paymentRepository,
            PaymentAttemptRepository paymentAttemptRepository,
            IdempotencyKeyRepository idempotencyKeyRepository,
            PaymentOutboxRepository paymentOutboxRepository,
            RiskEvaluator riskEvaluator,
            List<PaymentProvider> paymentProviders,
            ObjectMapper objectMapper) {
        this.paymentRepository = paymentRepository;
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.paymentOutboxRepository = paymentOutboxRepository;
        this.riskEvaluator = riskEvaluator;
        this.paymentProvider = paymentProviders.stream()
                .filter(p -> p.id() == Provider.MOCKPAY)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No MockPay provider configured"));
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ChargeResult charge(ChargeCommand command) {
        String requestHash = computeRequestHash(command);

        IdempotencyKeyEntity existing = idempotencyKeyRepository
                .findByIdMerchantIdAndIdIdempotencyKey(command.merchantId(), command.idempotencyKey())
                .orElse(null);

        if (existing != null) {
            if (existing.getResponseStatus() == RESPONSE_STATUS_COMPLETE) {
                return deserializeResult(existing.getResponseBody());
            }
            throw new IllegalStateException("Charge already in progress for idempotency key");
        }

        IdempotencyKeyEntity idempotencyKey = new IdempotencyKeyEntity();
        idempotencyKey.setId(new IdempotencyKeyId(command.merchantId(), command.idempotencyKey()));
        idempotencyKey.setRequestHash(requestHash);
        idempotencyKey.setResponseStatus(RESPONSE_STATUS_IN_FLIGHT);
        idempotencyKey.setResponseBody("{}");
        idempotencyKey.setExpiresAt(Instant.now().plus(72, ChronoUnit.HOURS));

        try {
            idempotencyKeyRepository.saveAndFlush(idempotencyKey);
        } catch (DataIntegrityViolationException ex) {
            IdempotencyKeyEntity raced = idempotencyKeyRepository
                    .findByIdMerchantIdAndIdIdempotencyKey(command.merchantId(), command.idempotencyKey())
                    .orElseThrow(() -> ex);
            if (raced.getResponseStatus() == RESPONSE_STATUS_COMPLETE) {
                return deserializeResult(raced.getResponseBody());
            }
            throw new IllegalStateException("Charge already in progress for idempotency key", ex);
        }

        PaymentEntity payment = new PaymentEntity();
        payment.setMerchantId(command.merchantId());
        payment.setStatus(PaymentStatus.CREATED);
        payment.setAmountCents(command.amountCents());
        payment.setCurrency(command.currency());
        payment = paymentRepository.save(payment);

        RiskEvaluator.RiskDecision risk = riskEvaluator.evaluate(new RiskEvaluator.RiskInput(
                payment.getId(),
                command.merchantId(),
                command.amountCents(),
                command.currency(),
                command.customerEmail()));

        payment.setStatus(PaymentStatus.RISK_EVALUATED);
        payment.setRiskScore(BigDecimal.valueOf(risk.score()));
        payment.setRiskRecommendation(risk.recommendation());
        payment.setRiskModelVersion(risk.modelVersion());
        payment.setRiskFallbackUsed(risk.fallbackUsed());
        paymentRepository.save(payment);

        payment.setStatus(PaymentStatus.AUTHORIZING);
        paymentRepository.save(payment);

        ProviderOutcome authorizeOutcome = paymentProvider.authorize(new PaymentProvider.AuthorizeRequest(
                payment.getId(),
                command.idempotencyKey(),
                command.amountCents(),
                command.currency()));

        ProviderOutcome captureOutcome = paymentProvider.capture(authorizeOutcome.providerRef());

        PaymentAttemptEntity attempt = new PaymentAttemptEntity();
        attempt.setPaymentId(payment.getId());
        attempt.setAttemptNumber((short) 1);
        attempt.setProvider(Provider.MOCKPAY.dbValue());
        attempt.setOutcome(ProviderOutcome.Outcome.CAPTURED.name());
        attempt.setProviderRef(captureOutcome.providerRef());
        attempt.setLatencyMs((int) (authorizeOutcome.latencyMs() + captureOutcome.latencyMs()));
        paymentAttemptRepository.save(attempt);

        payment.setStatus(PaymentStatus.COMPLETED);
        payment.setProvider(Provider.MOCKPAY.dbValue());
        paymentRepository.save(payment);

        String payloadJson = serializePayload(PaymentEventPayloads.paymentCompleted(
                command.merchantId(),
                command.correlationId(),
                payment.getId(),
                command.customerEmail(),
                command.amountCents(),
                Provider.MOCKPAY.dbValue()));

        PaymentOutboxEntity outbox = new PaymentOutboxEntity();
        outbox.setAggregate("payment");
        outbox.setAggregateId(payment.getId());
        outbox.setEventType("payment.completed");
        outbox.setPayload(payloadJson);
        paymentOutboxRepository.save(outbox);

        ChargeResult result = new ChargeResult(
                payment.getId(),
                PaymentStatus.COMPLETED,
                Provider.MOCKPAY,
                payment.getId());

        idempotencyKey.setPaymentId(payment.getId());
        idempotencyKey.setResponseStatus(RESPONSE_STATUS_COMPLETE);
        idempotencyKey.setResponseBody(serializeResult(result));
        idempotencyKeyRepository.save(idempotencyKey);

        return result;
    }

    private String computeRequestHash(ChargeCommand command) {
        String raw = command.merchantId()
                + "|" + command.amountCents()
                + "|" + command.currency()
                + "|" + command.customerEmail();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    private String serializeResult(ChargeResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize charge result", ex);
        }
    }

    private ChargeResult deserializeResult(String json) {
        try {
            return objectMapper.readValue(json, ChargeResult.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to deserialize charge result", ex);
        }
    }

    private String serializePayload(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize event payload", ex);
        }
    }
}
