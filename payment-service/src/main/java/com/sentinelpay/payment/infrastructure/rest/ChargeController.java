package com.sentinelpay.payment.infrastructure.rest;

import com.sentinelpay.common.error.ApiException;
import com.sentinelpay.common.error.ErrorCode;
import com.sentinelpay.common.web.CorrelationConstants;
import com.sentinelpay.payment.application.ChargeService;
import com.sentinelpay.payment.application.model.ChargeCommand;
import com.sentinelpay.payment.application.model.ChargeResult;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChargeController {

    private final ChargeService chargeService;

    public ChargeController(ChargeService chargeService) {
        this.chargeService = chargeService;
    }

    @PostMapping("/api/v1/payments/charge")
    @ResponseStatus(HttpStatus.CREATED)
    public ChargeResponse charge(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ChargeRequest body) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Idempotency-Key header is required");
        }

        String correlationId = MDC.get(CorrelationConstants.MDC_REQUEST_ID);
        ChargeCommand command = new ChargeCommand(
                body.merchantId(),
                body.amountCents(),
                body.currency(),
                body.customerEmail(),
                idempotencyKey,
                correlationId);

        ChargeResult result = chargeService.charge(command);
        return new ChargeResponse(result.paymentId(), result.status(), result.provider(), result.trailId());
    }
}
