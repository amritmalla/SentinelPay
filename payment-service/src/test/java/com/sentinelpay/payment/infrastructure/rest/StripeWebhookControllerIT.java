package com.sentinelpay.payment.infrastructure.rest;

import com.sentinelpay.common.error.GlobalExceptionHandler;
import com.sentinelpay.payment.PaymentTestContainers;
import com.sentinelpay.payment.config.SecurityConfig;
import com.sentinelpay.payment.domain.PaymentStatus;
import com.sentinelpay.payment.infrastructure.persistence.PaymentAttemptEntity;
import com.sentinelpay.payment.infrastructure.persistence.PaymentAttemptRepository;
import com.sentinelpay.payment.infrastructure.persistence.PaymentEntity;
import com.sentinelpay.payment.infrastructure.persistence.PaymentRepository;
import com.sentinelpay.payment.infrastructure.persistence.PaymentStatusHistoryRepository;
import com.sentinelpay.payment.infrastructure.persistence.ProcessedWebhookEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
@AutoConfigureMockMvc
@Import({GlobalExceptionHandler.class, SecurityConfig.class})
class StripeWebhookControllerIT {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    PaymentRepository paymentRepository;

    @Autowired
    PaymentAttemptRepository paymentAttemptRepository;

    @Autowired
    PaymentStatusHistoryRepository paymentStatusHistoryRepository;

    @Autowired
    ProcessedWebhookEventRepository processedWebhookEventRepository;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PaymentTestContainers.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", PaymentTestContainers.POSTGRES::getUsername);
        registry.add("spring.datasource.password", PaymentTestContainers.POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", PaymentTestContainers.KAFKA::getBootstrapServers);
    }

    @BeforeEach
    void clean() {
        processedWebhookEventRepository.deleteAll();
        paymentAttemptRepository.deleteAll();
        paymentStatusHistoryRepository.deleteAll();
        paymentRepository.deleteAll();
    }

    @Test
    void receive_validEvent_returns204() throws Exception {
        seedCompletedPayment("pi_ctrl_1");

        mockMvc.perform(post("/api/v1/webhooks/stripe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "id": "evt_ctrl",
                                  "type": "payment_intent.succeeded",
                                  "data": { "object": { "id": "pi_ctrl_1" } }
                                }
                                """))
                .andExpect(status().isNoContent());

        assertThat(processedWebhookEventRepository.count()).isOne();
        assertThat(paymentRepository.findAll().get(0).getStatus()).isEqualTo(PaymentStatus.COMPLETED);
    }

    @Test
    void receive_missingId_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/webhooks/stripe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type": "payment_intent.succeeded",
                                  "data": { "object": { "id": "pi_x" } }
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    private void seedCompletedPayment(String providerRef) {
        PaymentEntity payment = new PaymentEntity();
        payment.setMerchantId(UUID.randomUUID());
        payment.setStatus(PaymentStatus.COMPLETED);
        payment.setAmountCents(2_500);
        payment.setCurrency("USD");
        payment.setProvider("stripe");
        paymentRepository.saveAndFlush(payment);

        PaymentAttemptEntity attempt = new PaymentAttemptEntity();
        attempt.setPaymentId(payment.getId());
        attempt.setAttemptNumber((short) 1);
        attempt.setProvider("stripe");
        attempt.setOutcome("CAPTURED");
        attempt.setProviderRef(providerRef);
        paymentAttemptRepository.saveAndFlush(attempt);
    }
}
