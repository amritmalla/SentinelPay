package com.sentinelpay.payment.application.model;

import com.sentinelpay.payment.application.model.ChargeResult;

public sealed interface BeginChargeOutcome {

    record Stored(ChargeResult result) implements BeginChargeOutcome {
    }

    record Started(java.util.UUID paymentId) implements BeginChargeOutcome {
    }
}
