package com.sentinelpay.payment.application;

import com.sentinelpay.common.error.ApiException;
import com.sentinelpay.common.error.ErrorCode;
import com.sentinelpay.payment.infrastructure.persistence.PaymentEntity;

import java.util.UUID;

public final class MerchantOwnership {

    private MerchantOwnership() {
    }

    public static void assertOwned(UUID merchantId, PaymentEntity payment) {
        if (!payment.getMerchantId().equals(merchantId)) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "payment_not_found");
        }
    }
}
