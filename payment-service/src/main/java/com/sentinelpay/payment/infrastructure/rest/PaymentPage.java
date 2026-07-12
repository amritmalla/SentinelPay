package com.sentinelpay.payment.infrastructure.rest;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record PaymentPage(
        List<PaymentView> data,
        PageInfo page) {

    public record PageInfo(
            @JsonProperty("next_cursor") String nextCursor,
            int limit) {
    }
}
