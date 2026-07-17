package com.sentinelpay.payment.infrastructure.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelpay.common.error.ApiException;
import com.sentinelpay.common.error.ErrorCode;
import com.sentinelpay.common.error.GlobalExceptionHandler;
import com.sentinelpay.common.security.GatewaySecurityAutoConfiguration;
import com.sentinelpay.payment.application.ChargeService;
import com.sentinelpay.payment.application.model.ChargeResult;
import com.sentinelpay.payment.application.model.ChargeCommand;
import com.sentinelpay.payment.config.MerchantConfig;
import com.sentinelpay.payment.config.SecurityConfig;
import com.sentinelpay.payment.domain.PaymentStatus;
import com.sentinelpay.payment.domain.Provider;
import com.sentinelpay.payment.support.GatewayTestAuth;
import com.sentinelpay.payment.support.OpenApiContractSupport;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static com.sentinelpay.payment.support.GatewayTestAuth.asMerchant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChargeController.class)
@Import({GlobalExceptionHandler.class, SecurityConfig.class, GatewaySecurityAutoConfiguration.class, MerchantConfig.class})
@TestPropertySource(properties = {
        "sentinelpay.security.gateway-secret=" + GatewayTestAuth.SECRET,
        "sentinelpay.merchants.default-country=US"
})
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

        mockMvc.perform(asMerchant(post("/api/v1/payments/charge"), merchantId)
                        .header("Idempotency-Key", "demo-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount_cents": 2500,
                                  "currency": "USD",
                                  "customer_email": "buyer@example.com"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payment_id").value(paymentId.toString()))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.provider").value("MOCKPAY"))
                .andExpect(jsonPath("$.trail_id").value(paymentId.toString()))
                .andExpect(OpenApiContractSupport.openApi());
    }

    @Test
    void charge_missingGatewaySecret_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/payments/charge")
                        .header("Idempotency-Key", "demo-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount_cents": 2500,
                                  "currency": "USD",
                                  "customer_email": "buyer@example.com"
                                }
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void charge_missingIdempotencyKey_returns400() throws Exception {
        UUID merchantId = UUID.randomUUID();

        mockMvc.perform(asMerchant(post("/api/v1/payments/charge"), merchantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount_cents": 2500,
                                  "currency": "USD",
                                  "customer_email": "buyer@example.com"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void charge_badCurrency_returns400() throws Exception {
        UUID merchantId = UUID.randomUUID();

        mockMvc.perform(asMerchant(post("/api/v1/payments/charge"), merchantId)
                        .header("Idempotency-Key", "demo-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount_cents": 2500,
                                  "currency": "usd",
                                  "customer_email": "buyer@example.com"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void charge_idempotencyConflict_returns409() throws Exception {
        UUID merchantId = UUID.randomUUID();

        when(chargeService.charge(any())).thenThrow(new ApiException(
                ErrorCode.IDEMPOTENCY_CONFLICT, "Idempotency-Key reused with a different request"));

        mockMvc.perform(asMerchant(post("/api/v1/payments/charge"), merchantId)
                        .header("Idempotency-Key", "demo-conflict")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount_cents": 2500,
                                  "currency": "USD",
                                  "customer_email": "buyer@example.com"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void charge_inFlightDuplicate_returns409() throws Exception {
        UUID merchantId = UUID.randomUUID();

        when(chargeService.charge(any())).thenThrow(new ApiException(ErrorCode.CONFLICT, "charge_in_progress"));

        mockMvc.perform(asMerchant(post("/api/v1/payments/charge"), merchantId)
                        .header("Idempotency-Key", "demo-in-flight")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount_cents": 2500,
                                  "currency": "USD",
                                  "customer_email": "buyer@example.com"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"))
                .andExpect(jsonPath("$.error.message").value("charge_in_progress"));
    }

    @Test
    void charge_withRiskFeatureFields_threadsCategoryCardCountryAndResolvedMerchantCountry() throws Exception {
        UUID paymentId = UUID.randomUUID();
        UUID merchantId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        when(chargeService.charge(any())).thenReturn(new ChargeResult(
                paymentId, PaymentStatus.COMPLETED, Provider.MOCKPAY, paymentId));

        mockMvc.perform(asMerchant(post("/api/v1/payments/charge"), merchantId)
                        .header("Idempotency-Key", "demo-features")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount_cents": 2500,
                                  "currency": "USD",
                                  "customer_email": "buyer@example.com",
                                  "merchant_category": "retail",
                                  "card_country": "GB"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(OpenApiContractSupport.openApi());

        ArgumentCaptor<ChargeCommand> captor = ArgumentCaptor.forClass(ChargeCommand.class);
        verify(chargeService).charge(captor.capture());
        ChargeCommand command = captor.getValue();
        assertThat(command.merchantCategory()).isEqualTo("retail");
        assertThat(command.cardCountry()).isEqualTo("GB");
        assertThat(command.merchantCountry()).isEqualTo("US");
    }

    @Test
    void charge_omittedRiskFeatureFields_stillSucceeds() throws Exception {
        UUID paymentId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();

        when(chargeService.charge(any())).thenReturn(new ChargeResult(
                paymentId, PaymentStatus.COMPLETED, Provider.MOCKPAY, paymentId));

        mockMvc.perform(asMerchant(post("/api/v1/payments/charge"), merchantId)
                        .header("Idempotency-Key", "demo-compat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount_cents": 2500,
                                  "currency": "USD",
                                  "customer_email": "buyer@example.com"
                                }
                                """))
                .andExpect(status().isCreated());

        ArgumentCaptor<ChargeCommand> captor = ArgumentCaptor.forClass(ChargeCommand.class);
        verify(chargeService).charge(captor.capture());
        assertThat(captor.getValue().merchantCategory()).isNull();
        assertThat(captor.getValue().cardCountry()).isNull();
        assertThat(captor.getValue().merchantCountry()).isEqualTo("US");
    }
}
