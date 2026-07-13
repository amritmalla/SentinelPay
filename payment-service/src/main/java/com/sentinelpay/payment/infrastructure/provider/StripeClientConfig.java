package com.sentinelpay.payment.infrastructure.provider;

import com.stripe.StripeClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "sentinelpay.providers.stripe.mode", havingValue = "real")
public class StripeClientConfig {

    @Bean
    StripeClient stripeClient(
            @Value("${sentinelpay.providers.stripe.api-key}") String apiKey,
            @Value("${sentinelpay.providers.stripe.base-url}") String baseUrl) {
        return StripeClient.builder()
                .setApiKey(apiKey)
                .setApiBase(baseUrl)
                .build();
    }
}
