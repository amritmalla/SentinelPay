package com.sentinelpay.common.web;

public final class CorrelationConstants {

    private CorrelationConstants() {
    }

    /** Inbound/outbound correlation header propagated across the synchronous critical path. */
    public static final String HEADER_CORRELATION_ID = "X-Correlation-Id";

    /** MDC key surfaced in structured logs and the error envelope's {@code request_id}. */
    public static final String MDC_REQUEST_ID = "requestId";
}
