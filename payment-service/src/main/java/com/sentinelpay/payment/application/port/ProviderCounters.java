package com.sentinelpay.payment.application.port;

public interface ProviderCounters {

    int authorizationCount();

    int captureCount();

    int refundCount();

    void resetCounters();
}
