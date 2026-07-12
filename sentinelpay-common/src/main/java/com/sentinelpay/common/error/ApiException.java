package com.sentinelpay.common.error;

/**
 * Domain-to-transport exception. Services throw this with an {@link ErrorCode}; the
 * {@link GlobalExceptionHandler} maps it to the shared error envelope and HTTP status.
 */
public class ApiException extends RuntimeException {

    private final transient ErrorCode errorCode;

    public ApiException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
