package com.sentinelpay.payment.infrastructure.rest;

import com.sentinelpay.common.error.ApiException;
import com.sentinelpay.common.error.ErrorCode;
import com.sentinelpay.common.web.CorrelationConstants;
import com.sentinelpay.payment.application.RefundService;
import com.sentinelpay.payment.application.model.RefundCommand;
import com.sentinelpay.payment.application.model.RefundResult;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class RefundController {

    private final RefundService refundService;

    public RefundController(RefundService refundService) {
        this.refundService = refundService;
    }

    @PostMapping("/api/v1/payments/{paymentId}/refunds")
    @ResponseStatus(HttpStatus.CREATED)
    public RefundResponse createRefund(
            @PathVariable UUID paymentId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody RefundRequest body) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Idempotency-Key header is required");
        }

        String correlationId = MDC.get(CorrelationConstants.MDC_REQUEST_ID);
        RefundCommand command = new RefundCommand(
                paymentId,
                body.amountCents(),
                body.reason(),
                idempotencyKey,
                correlationId);

        RefundResult result = refundService.refund(command);
        return RefundResponse.from(result);
    }
}
