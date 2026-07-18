package com.sentinelpay.payment.infrastructure.rest;

import com.sentinelpay.common.security.CurrentMerchant;
import com.sentinelpay.payment.application.PaymentSummaryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class SummaryController {

    private final PaymentSummaryService paymentSummaryService;

    public SummaryController(PaymentSummaryService paymentSummaryService) {
        this.paymentSummaryService = paymentSummaryService;
    }

    @GetMapping("/api/v1/payments/{paymentId}/summary")
    public PaymentSummaryView getPaymentSummary(@PathVariable UUID paymentId) {
        return paymentSummaryService.getSummary(paymentId, CurrentMerchant.id());
    }
}
