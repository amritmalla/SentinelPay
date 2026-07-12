package com.sentinelpay.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Machine-readable API error body. Serialized as snake_case per the SentinelPay API conventions
 * (see docs/architecture/contracts/api-conventions.md).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        String code,
        String message,
        List<Map<String, Object>> details,
        @JsonProperty("request_id") String requestId
) {
}
