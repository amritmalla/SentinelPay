package com.sentinelpay.payment.infrastructure.grpc;

import com.sentinelpay.proto.fraud.RiskScoringServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.opentelemetry.instrumentation.grpc.v1_6.GrpcTelemetry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GrpcRiskClientConfig {

    @Bean(destroyMethod = "shutdownNow")
    ManagedChannel riskChannel(
            @Value("${sentinelpay.risk.grpc.target}") String target, GrpcTelemetry grpcTelemetry) {
        return ManagedChannelBuilder.forTarget(target)
                .usePlaintext()
                .intercept(grpcTelemetry.newClientInterceptor())
                .build();
    }

    @Bean
    RiskScoringServiceGrpc.RiskScoringServiceBlockingStub riskStub(ManagedChannel riskChannel) {
        return RiskScoringServiceGrpc.newBlockingStub(riskChannel);
    }
}
