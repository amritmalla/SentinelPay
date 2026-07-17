package com.sentinelpay.payment.application.routing;

import java.util.UUID;

public record RoutingContext(UUID paymentId, UUID merchantId, long amountCents, String currency) {
}
