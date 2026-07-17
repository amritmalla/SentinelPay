package com.sentinelpay.payment.infrastructure.rest;

import com.sentinelpay.common.error.ApiException;
import com.sentinelpay.common.error.ErrorCode;
import com.sentinelpay.common.security.CurrentMerchant;
import com.sentinelpay.common.web.CorrelationConstants;
import com.sentinelpay.payment.application.ChargeService;
import com.sentinelpay.payment.application.model.ChargeCommand;
import com.sentinelpay.payment.application.model.ChargeResult;
import com.sentinelpay.payment.config.MerchantCountryProperties;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class ChargeController {

    private final ChargeService chargeService;
    private final MerchantCountryProperties merchantCountries;

    public ChargeController(ChargeService chargeService, MerchantCountryProperties merchantCountries) {
        this.chargeService = chargeService;
        this.merchantCountries = merchantCountries;
    }

    @PostMapping("/api/v1/payments/charge")
    @ResponseStatus(HttpStatus.CREATED)
    public ChargeResponse charge(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ChargeRequest body) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Idempotency-Key header is required");
        }

        UUID merchantId = CurrentMerchant.id();
        String correlationId = MDC.get(CorrelationConstants.MDC_REQUEST_ID);
        ChargeCommand command = new ChargeCommand(
                merchantId,
                body.amountCents(),
                body.currency(),
                body.customerEmail(),
                idempotencyKey,
                correlationId,
                blankToNull(body.merchantCategory()),
                blankToNull(body.cardCountry()),
                merchantCountries.resolve(merchantId));

        ChargeResult result = chargeService.charge(command);
        return new ChargeResponse(result.paymentId(), result.status(), result.provider(), result.trailId());
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
