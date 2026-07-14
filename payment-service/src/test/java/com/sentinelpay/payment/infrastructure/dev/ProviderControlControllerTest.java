package com.sentinelpay.payment.infrastructure.dev;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelpay.common.security.GatewaySecurityAutoConfiguration;
import com.sentinelpay.payment.application.model.ProviderBehavior;
import com.sentinelpay.payment.application.model.ProviderOutcome.Outcome;
import com.sentinelpay.payment.config.DevSecurityConfig;
import com.sentinelpay.payment.config.SecurityConfig;
import com.sentinelpay.payment.domain.Provider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProviderControlController.class)
@Import({SecurityConfig.class, DevSecurityConfig.class, GatewaySecurityAutoConfiguration.class, ProviderBehavior.class})
@ActiveProfiles("dev")
class ProviderControlControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ProviderBehavior providerBehavior;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void program_mockpayHardFail_nextOutcomeIsHardFail() throws Exception {
        providerBehavior.reset();

        mockMvc.perform(post("/dev/providers/mockpay/program")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcomes\":[\"HARD_FAIL\"]}"))
                .andExpect(status().isOk());

        assertThat(providerBehavior.nextOutcome(Provider.MOCKPAY).outcome()).isEqualTo(Outcome.HARD_FAIL);
    }
}
