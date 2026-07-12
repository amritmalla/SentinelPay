package com.sentinelpay.payment.infrastructure.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelpay.common.error.GlobalExceptionHandler;
import com.sentinelpay.payment.application.ChargeService;
import com.sentinelpay.payment.application.model.ChargeResult;
import com.sentinelpay.payment.config.SecurityConfig;
import com.sentinelpay.payment.domain.PaymentStatus;
import com.sentinelpay.payment.domain.Provider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChargeController.class)
@Import({GlobalExceptionHandler.class, SecurityConfig.class})
class ChargeControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockBean
    ChargeService chargeService;

    @Test
    void charge_validRequest_returns201() throws Exception {
        UUID paymentId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();

        when(chargeService.charge(any())).thenReturn(new ChargeResult(
                paymentId, PaymentStatus.COMPLETED, Provider.MOCKPAY, paymentId));

        mockMvc.perform(post("/api/v1/payments/charge")
                        .header("Idempotency-Key", "demo-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "merchant_id": "%s",
                                  "amount_cents": 2500,
                                  "currency": "USD",
                                  "customer_email": "buyer@example.com"
                                }
                                """.formatted(merchantId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payment_id").value(paymentId.toString()))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.provider").value("MOCKPAY"))
                .andExpect(jsonPath("$.trail_id").value(paymentId.toString()));
    }

    @Test
    void charge_missingIdempotencyKey_returns400() throws Exception {
        UUID merchantId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/payments/charge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "merchant_id": "%s",
                                  "amount_cents": 2500,
                                  "currency": "USD",
                                  "customer_email": "buyer@example.com"
                                }
                                """.formatted(merchantId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void charge_badCurrency_returns400() throws Exception {
        UUID merchantId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/payments/charge")
                        .header("Idempotency-Key", "demo-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "merchant_id": "%s",
                                  "amount_cents": 2500,
                                  "currency": "usd",
                                  "customer_email": "buyer@example.com"
                                }
                                """.formatted(merchantId)))
                .andExpect(status().isBadRequest());
    }
}
