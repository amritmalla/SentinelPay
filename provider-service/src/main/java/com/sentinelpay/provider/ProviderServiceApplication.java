package com.sentinelpay.provider;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Provider Service — uniform provider abstraction (Stripe + MockPay adapters), authorize/capture,
 * error normalization, and rolling provider-health monitoring.
 */
@SpringBootApplication(scanBasePackages = "com.sentinelpay")
public class ProviderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProviderServiceApplication.class, args);
    }
}
