package com.sentinelpay.payment.infrastructure.provider;

import com.sentinelpay.payment.application.model.ProviderOutcome;
import com.sentinelpay.payment.application.model.ProviderOutcome.Outcome;
import com.sentinelpay.payment.application.port.PaymentProvider;
import com.sentinelpay.payment.domain.Provider;
import com.stripe.StripeClient;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.ApiException;
import com.stripe.exception.CardException;
import com.stripe.exception.IdempotencyException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.RateLimitException;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCaptureParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@ConditionalOnProperty(name = "sentinelpay.providers.stripe.mode", havingValue = "real")
public class StripeProvider implements PaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(StripeProvider.class);

    private final StripeClient stripeClient;
    private final String paymentMethod;

    public StripeProvider(
            StripeClient stripeClient,
            @Value("${sentinelpay.providers.stripe.payment-method:pm_card_visa}") String paymentMethod) {
        this.stripeClient = stripeClient;
        this.paymentMethod = paymentMethod;
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
            return mapAuthorizeException(ex, start);
        }
    }

    @Override
    public ProviderOutcome reconcile(ReconcileRequest request) {
        long start = System.nanoTime();
        try {
            PaymentIntent intent = createIntent(
                    request.paymentId(),
                    request.downstreamKey(),
                    request.amountCents(),
                    request.currency());
            return mapReconcileOutcome(intent, start);
        } catch (StripeException ex) {
            return mapReconcileException(ex, start);
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
            return mapAuthorizeException(ex, start);
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
            return mapAuthorizeException(ex, start);
        }
    }

    private PaymentIntent createIntent(AuthorizeRequest request) throws StripeException {
        return createIntent(
                request.paymentId(), request.downstreamKey(), request.amountCents(), request.currency());
    }

    private PaymentIntent createIntent(UUID paymentId, String downstreamKey, long amountCents, String currency)
            throws StripeException {
        // allow_redirects=never keeps this a server-side, non-redirect confirmation: without it a
        // Stripe account with dashboard-enabled payment methods rejects confirm=true unless a
        // return_url is supplied. Verified against Stripe test mode.
        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(amountCents)
                .setCurrency(currency.toLowerCase())
                .setCaptureMethod(PaymentIntentCreateParams.CaptureMethod.MANUAL)
                .setConfirm(true)
                .setAutomaticPaymentMethods(PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                        .setEnabled(true)
                        .setAllowRedirects(
                                PaymentIntentCreateParams.AutomaticPaymentMethods.AllowRedirects.NEVER)
                        .build())
                // SentinelPay's charge API carries no payment instrument (deliberate: keeps card data
                // and PCI scope out of the platform), so the real adapter confirms against a configured
                // Stripe test payment method. A production integration would instead take a
                // merchant-supplied payment-method token on the charge request.
                .setPaymentMethod(paymentMethod)
                .putMetadata("downstream_key", downstreamKey)
                .putMetadata("payment_id", paymentId.toString())
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
            case "processing" -> timed(startNanos, Outcome.AMBIGUOUS_TIMEOUT, null);
            case "canceled", "requires_payment_method" -> timed(startNanos, Outcome.NOT_AUTHORIZED, null);
            default -> timed(startNanos, Outcome.AMBIGUOUS_TIMEOUT, null);
        };
    }

    private ProviderOutcome mapAuthorizeException(StripeException ex, long startNanos) {
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

    private ProviderOutcome mapReconcileException(StripeException ex, long startNanos) {
        if (ex instanceof IdempotencyException) {
            log.error("Stripe reconcile idempotency error — param mismatch on replay; blocking failover", ex);
            return timed(startNanos, Outcome.AMBIGUOUS_TIMEOUT, null);
        }
        if (ex instanceof RateLimitException || ex instanceof ApiConnectionException) {
            return timed(startNanos, Outcome.RETRYABLE, null);
        }
        if (ex instanceof CardException) {
            return timed(startNanos, Outcome.HARD_FAIL, null);
        }
        if (ex instanceof InvalidRequestException) {
            return timed(startNanos, Outcome.AMBIGUOUS_TIMEOUT, null);
        }
        if (ex instanceof ApiException api && api.getStatusCode() >= 500) {
            return timed(startNanos, Outcome.AMBIGUOUS_TIMEOUT, null);
        }
        return timed(startNanos, Outcome.AMBIGUOUS_TIMEOUT, null);
    }

    private ProviderOutcome timed(long startNanos, Outcome outcome, String ref) {
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
        return new ProviderOutcome(outcome, ref, latencyMs);
    }
}
