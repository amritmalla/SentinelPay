package com.sentinelpay.common.error;

/**
 * The single error envelope shape returned by every service for every non-2xx response:
 * {@code { "error": { "code", "message", "details", "request_id" } }}.
 */
public record ErrorEnvelope(ApiError error) {

    public static ErrorEnvelope of(ApiError error) {
        return new ErrorEnvelope(error);
    }
}
