package com.sentinelpay.payment.infrastructure.grpc;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.grpc.v1_6.GrpcTelemetry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GrpcTelemetryConfig {

    @Bean
    GrpcTelemetry grpcTelemetry(OpenTelemetry openTelemetry) {
        return GrpcTelemetry.create(openTelemetry);
    }
}
