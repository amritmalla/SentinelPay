package com.sentinelpay.payment.infrastructure.rest;

import com.sentinelpay.payment.application.TrailService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class TrailController {

    private final TrailService trailService;

    public TrailController(TrailService trailService) {
        this.trailService = trailService;
    }

    @GetMapping("/api/v1/payments/{paymentId}/trail")
    public DecisionTrail getPaymentTrail(@PathVariable UUID paymentId) {
        return trailService.trail(paymentId);
    }
}
