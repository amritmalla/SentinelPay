package com.sentinelpay.payment.infrastructure.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.sentinelpay.payment.application.WebhookService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StripeWebhookController {

    private final WebhookService webhookService;

    public StripeWebhookController(WebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @PostMapping("/api/v1/webhooks/stripe")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void receive(@RequestBody JsonNode event) {
        webhookService.handle(event);
    }
}
