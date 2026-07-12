package com.sentinelpay.common.error;

import com.sentinelpay.common.web.CorrelationConstants;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps exceptions to the single {@link ErrorEnvelope} shape. Never leaks stack traces or
 * persistence internals to clients.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorEnvelope> handleApi(ApiException ex) {
        return build(ex.errorCode(), ex.getMessage(), List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorEnvelope> handleValidation(MethodArgumentNotValidException ex) {
        List<Map<String, Object>> details = new ArrayList<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("field", fe.getField());
            d.put("issue", fe.getDefaultMessage());
            details.add(d);
        }
        return build(ErrorCode.VALIDATION_FAILED, "Request validation failed", details);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorEnvelope> handleConstraint(ConstraintViolationException ex) {
        return build(ErrorCode.VALIDATION_FAILED, "Request validation failed", List.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorEnvelope> handleMethod(HttpRequestMethodNotSupportedException ex) {
        return build(ErrorCode.METHOD_NOT_ALLOWED, "HTTP method not supported", List.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorEnvelope> handleNotFound(NoResourceFoundException ex) {
        return build(ErrorCode.RESOURCE_NOT_FOUND, "Resource not found", List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorEnvelope> handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);
        return build(ErrorCode.INTERNAL, "An unexpected error occurred", List.of());
    }

    private ResponseEntity<ErrorEnvelope> build(ErrorCode code, String message, List<Map<String, Object>> details) {
        String requestId = MDC.get(CorrelationConstants.MDC_REQUEST_ID);
        ApiError error = new ApiError(code.name(), message, details.isEmpty() ? null : details, requestId);
        return ResponseEntity.status(code.status()).body(ErrorEnvelope.of(error));
    }
}
