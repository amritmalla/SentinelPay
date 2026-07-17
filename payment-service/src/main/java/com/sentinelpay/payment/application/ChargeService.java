package com.sentinelpay.payment.application;

import com.sentinelpay.payment.application.model.BeginChargeOutcome;
import com.sentinelpay.payment.application.model.ChargeCommand;
import com.sentinelpay.payment.application.model.ChargeResult;
import com.sentinelpay.payment.application.model.DecisionResult;
import com.sentinelpay.payment.application.model.ProviderOutcome;
import com.sentinelpay.payment.application.model.ProviderOutcome.Outcome;
import com.sentinelpay.payment.application.port.PaymentProvider;
import com.sentinelpay.payment.application.port.RiskEvaluator;
import com.sentinelpay.payment.domain.PaymentStatus;
import com.sentinelpay.payment.domain.Provider;
import com.sentinelpay.payment.infrastructure.metrics.ChargeMetrics;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ChargeService {

    private final PaymentTransactionService paymentTransactionService;
    private final RiskEvaluator riskEvaluator;
    private final ProviderRouting providerRouting;
    private final Map<Provider, PaymentProvider> providers;
    private final ChargeMetrics chargeMetrics;

    public ChargeService(
            PaymentTransactionService paymentTransactionService,
            RiskEvaluator riskEvaluator,
            ProviderRouting providerRouting,
            List<PaymentProvider> paymentProviders,
            ChargeMetrics chargeMetrics) {
        this.paymentTransactionService = paymentTransactionService;
        this.riskEvaluator = riskEvaluator;
        this.providerRouting = providerRouting;
        this.providers = paymentProviders.stream()
                .collect(Collectors.toMap(PaymentProvider::id, Function.identity()));
        this.chargeMetrics = chargeMetrics;
    }

    public ChargeResult charge(ChargeCommand command) {
        return chargeMetrics.recordCharge(() -> doCharge(command));
    }

    private ChargeResult doCharge(ChargeCommand command) {
        BeginChargeOutcome begin = paymentTransactionService.beginCharge(command);
        if (begin instanceof BeginChargeOutcome.Stored stored) {
            return stored.result();
        }

        UUID paymentId = ((BeginChargeOutcome.Started) begin).paymentId();

        RiskEvaluator.RiskDecision risk = riskEvaluator.evaluate(new RiskEvaluator.RiskInput(
                paymentId,
                command.merchantId(),
                command.amountCents(),
                command.currency(),
                command.customerEmail(),
                command.merchantCategory(),
                command.cardCountry(),
                command.merchantCountry()));

        if (risk.fallbackUsed()) {
            chargeMetrics.recordRiskFallback();
        }

        DecisionResult decision = paymentTransactionService.recordDecision(paymentId, command, risk);
        if (decision.isTerminal()) {
            return decision.terminalResult();
        }

        short attemptNumber = 0;
        for (Provider providerSlot : providerRouting.ordered()) {
            attemptNumber++;
            PaymentProvider provider = providers.get(providerSlot);
            if (provider == null) {
                continue;
            }

            String downstreamKey = downstreamKey(paymentId, providerSlot, attemptNumber);
            paymentTransactionService.startAttempt(paymentId, providerSlot, attemptNumber, downstreamKey);

            boolean reconciledFromAmbiguous = false;
            ProviderOutcome authorizeOutcome = chargeMetrics.recordProviderCall(
                    providerSlot, "authorize", () -> provider.authorize(new PaymentProvider.AuthorizeRequest(
                            paymentId, downstreamKey, command.amountCents(), command.currency())));

            Outcome outcome = authorizeOutcome.outcome();
            String providerRef = authorizeOutcome.providerRef();
            if (outcome == Outcome.AMBIGUOUS_TIMEOUT) {
                ProviderOutcome reconciled = chargeMetrics.recordProviderCall(
                        providerSlot, "reconcile", () -> provider.reconcile(downstreamKey));
                outcome = reconciled.outcome();
                providerRef = reconciled.providerRef();
                reconciledFromAmbiguous = true;
                chargeMetrics.recordProviderAuthorization(providerSlot, outcome.name());
            } else {
                chargeMetrics.recordProviderAuthorization(providerSlot, outcome.name());
            }

            switch (outcome) {
                case AUTHORIZED -> {
                    if (attemptNumber > 1 || reconciledFromAmbiguous) {
                        chargeMetrics.recordRecoveredAuthorization(providerSlot);
                    }
                    final String captureRef = providerRef;
                    ProviderOutcome captureOutcome = chargeMetrics.recordProviderCall(
                            providerSlot, "capture", () -> provider.capture(captureRef));
                    return paymentTransactionService.completePayment(
                            paymentId,
                            providerSlot,
                            captureOutcome.providerRef(),
                            attemptNumber,
                            command);
                }
                case HARD_FAIL, RETRYABLE, NOT_AUTHORIZED -> {
                    paymentTransactionService.recordAttemptOutcome(
                            paymentId, attemptNumber, persistableAttemptOutcome(outcome), null);
                }
                default -> paymentTransactionService.recordAttemptOutcome(
                        paymentId, attemptNumber, persistableAttemptOutcome(outcome), authorizeOutcome.providerRef());
            }
        }

        return paymentTransactionService.failPayment(paymentId, "all_providers_exhausted", command);
    }

    private static Outcome persistableAttemptOutcome(Outcome outcome) {
        return outcome == Outcome.NOT_AUTHORIZED ? Outcome.HARD_FAIL : outcome;
    }

    static String downstreamKey(UUID paymentId, Provider provider, short attemptNumber) {
        return paymentId + ":" + provider.dbValue() + ":" + attemptNumber;
    }
}
