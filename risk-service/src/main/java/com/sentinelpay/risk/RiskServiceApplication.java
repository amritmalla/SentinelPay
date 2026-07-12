package com.sentinelpay.risk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Risk Service — staged, pluggable risk-evaluation pipeline (rule-based scorer in v1) producing
 * explainable assessments. Serves the Payment Service over gRPC on the critical path.
 */
@SpringBootApplication(scanBasePackages = "com.sentinelpay")
public class RiskServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(RiskServiceApplication.class, args);
    }
}
