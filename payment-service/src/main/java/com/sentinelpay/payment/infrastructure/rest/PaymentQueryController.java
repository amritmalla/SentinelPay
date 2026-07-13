package com.sentinelpay.payment.infrastructure.rest;

import com.sentinelpay.common.security.CurrentMerchant;
import com.sentinelpay.payment.application.PaymentQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class PaymentQueryController {

    private final PaymentQueryService paymentQueryService;

    public PaymentQueryController(PaymentQueryService paymentQueryService) {
        this.paymentQueryService = paymentQueryService;
    }

    @GetMapping("/api/v1/payments/{paymentId}")
    public PaymentView getPayment(@PathVariable UUID paymentId) {
        return paymentQueryService.getPayment(paymentId, CurrentMerchant.id());
    }

    @GetMapping("/api/v1/payments")
    public PaymentPage listPayments(
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return paymentQueryService.listPayments(CurrentMerchant.id(), cursor, limit);
    }
}
