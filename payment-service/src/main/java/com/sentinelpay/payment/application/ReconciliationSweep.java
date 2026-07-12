package com.sentinelpay.payment.application;

import com.sentinelpay.payment.application.model.ChargeCommand;
import com.sentinelpay.payment.application.model.ProviderOutcome.Outcome;
import com.sentinelpay.payment.application.port.PaymentProvider;
import com.sentinelpay.payment.domain.Provider;
import com.sentinelpay.payment.infrastructure.persistence.IdempotencyKeyEntity;
import com.sentinelpay.payment.infrastructure.persistence.IdempotencyKeyRepository;
import com.sentinelpay.payment.infrastructure.persistence.PaymentAttemptEntity;
import com.sentinelpay.payment.infrastructure.persistence.PaymentAttemptRepository;
import com.sentinelpay.payment.infrastructure.persistence.PaymentEntity;
import com.sentinelpay.payment.infrastructure.persistence.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class ReconciliationSweep {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationSweep.class);

    private final PaymentRepository paymentRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final PaymentTransactionService paymentTransactionService;
    private final Map<Provider, PaymentProvider> providers;
    private final long cutoffSeconds;

    public ReconciliationSweep(
            PaymentRepository paymentRepository,
            PaymentAttemptRepository paymentAttemptRepository,
            IdempotencyKeyRepository idempotencyKeyRepository,
            PaymentTransactionService paymentTransactionService,
            List<PaymentProvider> paymentProviders,
            @Value("${sentinelpay.reconciliation.sweep.cutoff-seconds:30}") long cutoffSeconds) {
        this.paymentRepository = paymentRepository;
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.paymentTransactionService = paymentTransactionService;
        this.providers = paymentProviders.stream()
                .collect(Collectors.toMap(PaymentProvider::id, Function.identity()));
        this.cutoffSeconds = cutoffSeconds;
    }

    @Scheduled(fixedDelayString = "${sentinelpay.reconciliation.sweep.delay-ms:30000}")
    public void scheduledSweep() {
        sweep(Instant.now().minus(cutoffSeconds, ChronoUnit.SECONDS));
    }

    public void sweep(Instant cutoff) {
        List<UUID> stuck = paymentRepository.findStuckAuthorizing(cutoff);
        for (UUID paymentId : stuck) {
            try {
                reconcilePayment(paymentId);
            } catch (IllegalStateException ex) {
                log.debug("Reconciliation race for payment {}: {}", paymentId, ex.getMessage());
            } catch (Exception ex) {
                log.warn("Reconciliation sweep skipped payment {}: {}", paymentId, ex.getMessage());
            }
        }
    }

    private void reconcilePayment(UUID paymentId) {
        List<PaymentAttemptEntity> attempts = paymentAttemptRepository.findByPaymentId(paymentId);
        PaymentAttemptEntity latestStarted = attempts.stream()
                .filter(a -> "STARTED".equals(a.getOutcome()))
                .max(Comparator.comparing(PaymentAttemptEntity::getAttemptNumber))
                .orElse(null);

        if (latestStarted == null || latestStarted.getDownstreamKey() == null) {
            return;
        }

        Provider provider = Provider.fromDbValue(latestStarted.getProvider());
        PaymentProvider paymentProvider = providers.get(provider);
        if (paymentProvider == null) {
            return;
        }

        var reconciled = paymentProvider.reconcile(latestStarted.getDownstreamKey());
        ChargeCommand command = chargeCommandFor(paymentId);

        if (reconciled.outcome() == Outcome.AUTHORIZED) {
            var capture = paymentProvider.capture(reconciled.providerRef());
            paymentTransactionService.completePayment(
                    paymentId,
                    provider,
                    capture.providerRef(),
                    latestStarted.getAttemptNumber(),
                    command);
        } else {
            paymentTransactionService.failPayment(paymentId, "reconciled_not_authorized", command);
        }
    }

    private ChargeCommand chargeCommandFor(UUID paymentId) {
        PaymentEntity payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalStateException("Payment not found: " + paymentId));
        IdempotencyKeyEntity key = idempotencyKeyRepository.findByPaymentId(paymentId)
                .orElseThrow(() -> new IllegalStateException("Idempotency key not found for payment: " + paymentId));
        return new ChargeCommand(
                payment.getMerchantId(),
                payment.getAmountCents(),
                payment.getCurrency(),
                "sweep@reconcile.local",
                key.getId().getIdempotencyKey(),
                "sweep-" + paymentId);
    }
}
