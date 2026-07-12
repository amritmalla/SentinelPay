package com.sentinelpay.payment.infrastructure.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelpay.common.error.ApiException;
import com.sentinelpay.common.error.ErrorCode;
import com.sentinelpay.common.error.GlobalExceptionHandler;
import com.sentinelpay.payment.application.RefundService;
import com.sentinelpay.payment.application.model.RefundResult;
import com.sentinelpay.payment.config.SecurityConfig;
import com.sentinelpay.payment.domain.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RefundController.class)
@Import({GlobalExceptionHandler.class, SecurityConfig.class})
class RefundControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockBean
    RefundService refundService;

    @Test
    void createRefund_validRequest_returns201() throws Exception {
        UUID paymentId = UUID.randomUUID();
        UUID refundId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-07-12T12:00:00Z");

        when(refundService.refund(any())).thenReturn(new RefundResult(
                refundId, paymentId, 2_500, "REFUNDED", "customer_request", PaymentStatus.REFUNDED, createdAt));

        mockMvc.perform(post("/api/v1/payments/{paymentId}/refunds", paymentId)
                        .header("Idempotency-Key", "refund-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount_cents": 2500,
                                  "reason": "customer_request"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.refund_id").value(refundId.toString()))
                .andExpect(jsonPath("$.payment_id").value(paymentId.toString()))
                .andExpect(jsonPath("$.amount_cents").value(2500))
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.reason").value("customer_request"))
                .andExpect(jsonPath("$.created_at").value("2026-07-12T12:00:00Z"));
    }

    @Test
    void createRefund_missingIdempotencyKey_returns400() throws Exception {
        UUID paymentId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/payments/{paymentId}/refunds", paymentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount_cents": 2500
                                }
                                """))
                .andExpect(status().isBadRequest());
    }
}
