package com.sentinelpay.payment.infrastructure.provider;

import com.sentinelpay.payment.application.model.ProviderOutcome;
import com.sentinelpay.payment.application.model.ProviderOutcome.Outcome;
import com.sentinelpay.payment.application.port.PaymentProvider;
import com.sentinelpay.payment.domain.Provider;
import com.stripe.StripeClient;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.ApiException;
import com.stripe.exception.CardException;
import com.stripe.exception.RateLimitException;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCaptureParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "sentinelpay.providers.stripe.mode", havingValue = "real")
public class StripeProvider implements PaymentProvider {

    private final StripeClient stripeClient;

    public StripeProvider(StripeClient stripeClient) {
        this.stripeClient = stripeClient;
    }

    @Override
    public Provider id() {
        return Provider.STRIPE;
    }

    @Override
    public ProviderOutcome authorize(AuthorizeRequest request) {
        long start = System.nanoTime();
        try {
            PaymentIntent intent = createIntent(request);
            return mapAuthorizeOutcome(intent, start);
        } catch (StripeException ex) {
            return mapException(ex, start);
        }
    }

    @Override
    public ProviderOutcome reconcile(String downstreamKey) {
        long start = System.nanoTime();
        try {
            PaymentIntent intent = createIntent(downstreamKey, 0, "usd");
            return mapReconcileOutcome(intent, start);
        } catch (StripeException ex) {
            return mapException(ex, start);
        }
    }

    @Override
    public ProviderOutcome capture(String providerRef) {
        long start = System.nanoTime();
        try {
            PaymentIntent intent = stripeClient
                    .paymentIntents()
                    .capture(providerRef, PaymentIntentCaptureParams.builder().build());
            return timed(start, Outcome.CAPTURED, intent.getId());
        } catch (StripeException ex) {
            return mapException(ex, start);
        }
    }

    @Override
    public ProviderOutcome refund(String providerRef, long amountCents) {
        long start = System.nanoTime();
        try {
            Refund refund = stripeClient.refunds().create(RefundCreateParams.builder()
                    .setPaymentIntent(providerRef)
                    .setAmount(amountCents)
                    .build());
            return timed(start, Outcome.REFUNDED, refund.getId());
        } catch (StripeException ex) {
            return mapException(ex, start);
        }
    }

    private PaymentIntent createIntent(AuthorizeRequest request) throws StripeException {
        return createIntent(request.downstreamKey(), request.amountCents(), request.currency());
    }

    private PaymentIntent createIntent(String downstreamKey, long amountCents, String currency)
            throws StripeException {
        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(amountCents)
                .setCurrency(currency.toLowerCase())
                .setCaptureMethod(PaymentIntentCreateParams.CaptureMethod.MANUAL)
                .setConfirm(true)
                .build();

        RequestOptions options = RequestOptions.builder()
                .setIdempotencyKey(downstreamKey)
                .build();

        return stripeClient.paymentIntents().create(params, options);
    }

    private ProviderOutcome mapAuthorizeOutcome(PaymentIntent intent, long startNanos) {
        return switch (intent.getStatus()) {
            case "requires_capture", "succeeded" -> timed(startNanos, Outcome.AUTHORIZED, intent.getId());
            case "processing" -> timed(startNanos, Outcome.AMBIGUOUS_TIMEOUT, null);
            default -> timed(startNanos, Outcome.HARD_FAIL, null);
        };
    }

    private ProviderOutcome mapReconcileOutcome(PaymentIntent intent, long startNanos) {
        return switch (intent.getStatus()) {
            case "requires_capture", "succeeded" -> timed(startNanos, Outcome.AUTHORIZED, intent.getId());
            default -> timed(startNanos, Outcome.NOT_AUTHORIZED, null);
        };
    }

    private ProviderOutcome mapException(StripeException ex, long startNanos) {
        if (ex instanceof RateLimitException || ex instanceof ApiConnectionException) {
            return timed(startNanos, Outcome.RETRYABLE, null);
        }
        if (ex instanceof CardException) {
            return timed(startNanos, Outcome.HARD_FAIL, null);
        }
        if (ex instanceof ApiException api && api.getStatusCode() >= 500) {
            return timed(startNanos, Outcome.AMBIGUOUS_TIMEOUT, null);
        }
        return timed(startNanos, Outcome.HARD_FAIL, null);
    }

    private ProviderOutcome timed(long startNanos, Outcome outcome, String ref) {
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
        return new ProviderOutcome(outcome, ref, latencyMs);
    }
}
