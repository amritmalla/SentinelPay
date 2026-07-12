package com.sentinelpay.payment.application;

import com.sentinelpay.payment.application.model.BeginRefundOutcome;
import com.sentinelpay.payment.application.model.ProviderOutcome;
import com.sentinelpay.payment.application.model.RefundCommand;
import com.sentinelpay.payment.application.model.RefundResult;
import com.sentinelpay.payment.application.port.PaymentProvider;
import com.sentinelpay.payment.domain.Provider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class RefundService {

    private final PaymentTransactionService paymentTransactionService;
    private final Map<Provider, PaymentProvider> providers;

    public RefundService(
            PaymentTransactionService paymentTransactionService,
            List<PaymentProvider> paymentProviders) {
        this.paymentTransactionService = paymentTransactionService;
        this.providers = paymentProviders.stream()
                .collect(Collectors.toMap(PaymentProvider::id, Function.identity()));
    }

    public RefundResult refund(RefundCommand command) {
        BeginRefundOutcome begin = paymentTransactionService.beginRefund(command);
        if (begin instanceof BeginRefundOutcome.Stored stored) {
            return stored.result();
        }

        BeginRefundOutcome.RefundContext context = ((BeginRefundOutcome.Started) begin).context();
        Provider provider = Provider.fromDbValue(context.provider());
        PaymentProvider paymentProvider = providers.get(provider);
        if (paymentProvider == null) {
            throw new IllegalStateException("No provider adapter for " + context.provider());
        }

        ProviderOutcome outcome = paymentProvider.refund(context.providerRef(), context.amountCents());
        return paymentTransactionService.completeRefund(context, outcome);
    }
}
