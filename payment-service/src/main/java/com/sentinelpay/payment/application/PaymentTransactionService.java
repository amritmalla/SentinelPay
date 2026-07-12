package com.sentinelpay.payment.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelpay.common.error.ApiException;
import com.sentinelpay.common.error.ErrorCode;
import com.sentinelpay.payment.application.model.BeginChargeOutcome;
import com.sentinelpay.payment.application.model.ChargeCommand;
import com.sentinelpay.payment.application.model.ChargeResult;
import com.sentinelpay.payment.application.model.DecisionResult;
import com.sentinelpay.payment.application.model.PaymentDecision;
import com.sentinelpay.payment.application.model.ProviderOutcome.Outcome;
import com.sentinelpay.payment.application.port.RiskEvaluator;
import com.sentinelpay.payment.domain.PaymentStateMachine;
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
import com.sentinelpay.payment.infrastructure.persistence.PaymentStatusHistoryEntity;
import com.sentinelpay.payment.infrastructure.persistence.PaymentStatusHistoryRepository;
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
import java.util.UUID;

@Service
public class PaymentTransactionService {

    private static final short RESPONSE_STATUS_COMPLETE = 200;
    private static final short RESPONSE_STATUS_IN_FLIGHT = 0;

    private final PaymentRepository paymentRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final PaymentOutboxRepository paymentOutboxRepository;
    private final PaymentStatusHistoryRepository paymentStatusHistoryRepository;
    private final ObjectMapper objectMapper;

    public PaymentTransactionService(
            PaymentRepository paymentRepository,
            PaymentAttemptRepository paymentAttemptRepository,
            IdempotencyKeyRepository idempotencyKeyRepository,
            PaymentOutboxRepository paymentOutboxRepository,
            PaymentStatusHistoryRepository paymentStatusHistoryRepository,
            ObjectMapper objectMapper) {
        this.paymentRepository = paymentRepository;
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.paymentOutboxRepository = paymentOutboxRepository;
        this.paymentStatusHistoryRepository = paymentStatusHistoryRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public BeginChargeOutcome beginCharge(ChargeCommand command) {
        String requestHash = computeRequestHash(command);

        IdempotencyKeyEntity existing = idempotencyKeyRepository
                .findByIdMerchantIdAndIdIdempotencyKey(command.merchantId(), command.idempotencyKey())
                .orElse(null);

        if (existing != null) {
            return resolveExistingKey(existing, requestHash);
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
            return resolveExistingKey(raced, requestHash);
        }

        PaymentEntity payment = new PaymentEntity();
        payment.setMerchantId(command.merchantId());
        payment.setStatus(PaymentStatus.CREATED);
        payment.setAmountCents(command.amountCents());
        payment.setCurrency(command.currency());
        payment = paymentRepository.saveAndFlush(payment);

        recordHistory(payment.getId(), null, PaymentStatus.CREATED);

        idempotencyKey.setPaymentId(payment.getId());
        idempotencyKeyRepository.save(idempotencyKey);

        return new BeginChargeOutcome.Started(payment.getId());
    }

    @Transactional
    public DecisionResult recordDecision(UUID paymentId, ChargeCommand command, RiskEvaluator.RiskDecision risk) {
        PaymentEntity payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new IllegalStateException("Payment not found: " + paymentId));

        transition(payment, PaymentStatus.RISK_EVALUATED);
        payment.setRiskScore(BigDecimal.valueOf(risk.score()));
        payment.setRiskRecommendation(risk.recommendation());
        payment.setRiskModelVersion(risk.modelVersion());
        payment.setRiskFallbackUsed(risk.fallbackUsed());
        paymentRepository.save(payment);

        return switch (risk.recommendation()) {
            case "BLOCK" -> {
                transition(payment, PaymentStatus.BLOCKED);
                writeFailedOutbox(command, paymentId, "risk_block");
                ChargeResult result = finalizeIdempotency(command, paymentId, PaymentStatus.BLOCKED, null);
                yield new DecisionResult(PaymentDecision.BLOCK, result);
            }
            case "REVIEW" -> {
                transition(payment, PaymentStatus.IN_REVIEW);
                ChargeResult result = finalizeIdempotency(command, paymentId, PaymentStatus.IN_REVIEW, null);
                yield new DecisionResult(PaymentDecision.IN_REVIEW, result);
            }
            default -> DecisionResult.approve();
        };
    }

    @Transactional
    public void startAttempt(UUID paymentId, Provider provider, short attemptNumber, String downstreamKey) {
        PaymentEntity payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new IllegalStateException("Payment not found: " + paymentId));

        if (payment.getStatus() == PaymentStatus.RISK_EVALUATED) {
            transition(payment, PaymentStatus.AUTHORIZING);
        } else if (payment.getStatus() != PaymentStatus.AUTHORIZING) {
            throw new IllegalStateException("Cannot start attempt from status " + payment.getStatus());
        }

        PaymentAttemptEntity attempt = new PaymentAttemptEntity();
        attempt.setPaymentId(paymentId);
        attempt.setAttemptNumber(attemptNumber);
        attempt.setProvider(provider.dbValue());
        attempt.setOutcome("STARTED");
        attempt.setDownstreamKey(downstreamKey);
        paymentAttemptRepository.saveAndFlush(attempt);
    }

    @Transactional
    public void recordAttemptOutcome(
            UUID paymentId, short attemptNumber, Outcome outcome, String providerRef) {
        PaymentAttemptEntity attempt = paymentAttemptRepository
                .findByPaymentIdAndAttemptNumber(paymentId, attemptNumber)
                .orElseThrow(() -> new IllegalStateException("Attempt not found: " + paymentId + "/" + attemptNumber));
        attempt.setOutcome(outcome.name());
        attempt.setProviderRef(providerRef);
        paymentAttemptRepository.save(attempt);
    }

    @Transactional
    public ChargeResult completePayment(
            UUID paymentId,
            Provider provider,
            String providerRef,
            short attemptNumber,
            ChargeCommand command) {
        PaymentEntity payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new IllegalStateException("Payment not found: " + paymentId));

        transition(payment, PaymentStatus.AUTHORIZED);
        transition(payment, PaymentStatus.CAPTURED);

        PaymentAttemptEntity attempt = paymentAttemptRepository
                .findByPaymentIdAndAttemptNumber(paymentId, attemptNumber)
                .orElseThrow(() -> new IllegalStateException("Attempt not found: " + paymentId + "/" + attemptNumber));
        attempt.setOutcome(Outcome.CAPTURED.name());
        attempt.setProviderRef(providerRef);
        paymentAttemptRepository.save(attempt);

        transition(payment, PaymentStatus.COMPLETED);
        payment.setProvider(provider.dbValue());
        paymentRepository.save(payment);

        PaymentOutboxEntity outbox = new PaymentOutboxEntity();
        outbox.setAggregate("payment");
        outbox.setAggregateId(paymentId);
        outbox.setEventType("payment.completed");
        outbox.setPayload(serializePayload(PaymentEventPayloads.paymentCompleted(
                command.merchantId(),
                command.correlationId(),
                paymentId,
                command.customerEmail(),
                command.amountCents(),
                provider.dbValue())));
        paymentOutboxRepository.save(outbox);

        return finalizeIdempotency(command, paymentId, PaymentStatus.COMPLETED, provider);
    }

    @Transactional
    public ChargeResult failPayment(UUID paymentId, String reason, ChargeCommand command) {
        PaymentEntity payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new IllegalStateException("Payment not found: " + paymentId));

        transition(payment, PaymentStatus.FAILED);
        payment.setFailureReason(reason);
        paymentRepository.save(payment);

        writeFailedOutbox(command, paymentId, reason);
        return finalizeIdempotency(command, paymentId, PaymentStatus.FAILED, null);
    }

    private BeginChargeOutcome resolveExistingKey(IdempotencyKeyEntity existing, String requestHash) {
        if (!existing.getRequestHash().equals(requestHash)) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT, "Idempotency-Key reused with a different request");
        }
        if (existing.getResponseStatus() == RESPONSE_STATUS_COMPLETE) {
            return new BeginChargeOutcome.Stored(deserializeResult(existing.getResponseBody()));
        }
        throw new ApiException(ErrorCode.CONFLICT, "charge_in_progress");
    }

    private void transition(PaymentEntity payment, PaymentStatus to) {
        PaymentStatus from = payment.getStatus();
        PaymentStateMachine.assertTransition(from, to);
        payment.setStatus(to);
        paymentRepository.save(payment);
        recordHistory(payment.getId(), from, to);
    }

    private void recordHistory(UUID paymentId, PaymentStatus from, PaymentStatus to) {
        PaymentStatusHistoryEntity history = new PaymentStatusHistoryEntity();
        history.setPaymentId(paymentId);
        history.setFromStatus(from == null ? null : from.name());
        history.setToStatus(to.name());
        paymentStatusHistoryRepository.save(history);
    }

    private ChargeResult finalizeIdempotency(
            ChargeCommand command, UUID paymentId, PaymentStatus status, Provider provider) {
        ChargeResult result = new ChargeResult(paymentId, status, provider, paymentId);

        IdempotencyKeyEntity key = idempotencyKeyRepository
                .findByIdMerchantIdAndIdIdempotencyKey(command.merchantId(), command.idempotencyKey())
                .orElseThrow(() -> new IllegalStateException("Idempotency key not found"));

        key.setPaymentId(paymentId);
        key.setResponseStatus(RESPONSE_STATUS_COMPLETE);
        key.setResponseBody(serializeResult(result));
        idempotencyKeyRepository.save(key);

        return result;
    }

    private void writeFailedOutbox(ChargeCommand command, UUID paymentId, String reason) {
        PaymentOutboxEntity outbox = new PaymentOutboxEntity();
        outbox.setAggregate("payment");
        outbox.setAggregateId(paymentId);
        outbox.setEventType("payment.failed");
        outbox.setPayload(serializePayload(PaymentEventPayloads.paymentFailed(
                command.merchantId(),
                command.correlationId(),
                paymentId,
                command.customerEmail(),
                reason)));
        paymentOutboxRepository.save(outbox);
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
