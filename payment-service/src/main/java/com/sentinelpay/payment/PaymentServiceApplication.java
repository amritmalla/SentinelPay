package com.sentinelpay.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Payment Service — critical-path orchestrator and system of record for the payment lifecycle.
 * Scans {@code com.sentinelpay} so the shared web baseline (error handling, correlation) is applied.
 */
@SpringBootApplication(scanBasePackages = "com.sentinelpay")
public class PaymentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}
