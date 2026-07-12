package com.sentinelpay.risk.infrastructure.grpc;

import com.sentinelpay.proto.fraud.RiskScoreRequest;
import com.sentinelpay.proto.fraud.RiskScoreResponse;
import com.sentinelpay.proto.fraud.RiskScoringServiceGrpc;
import com.sentinelpay.risk.RiskTestContainers;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.task.scheduling.enabled=false",
        "sentinelpay.risk.grpc.port=0"
})
class RiskScoringServerIT {

    private static ManagedChannel channel;

    @Autowired
    GrpcServerLifecycle grpcServerLifecycle;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", RiskTestContainers.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", RiskTestContainers.POSTGRES::getUsername);
        registry.add("spring.datasource.password", RiskTestContainers.POSTGRES::getPassword);
        registry.add("spring.data.redis.host", RiskTestContainers.REDIS::getHost);
        registry.add("spring.data.redis.port", () -> RiskTestContainers.REDIS.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers", RiskTestContainers.KAFKA::getBootstrapServers);
    }

    @AfterAll
    static void shutdownChannel() {
        if (channel != null) {
            channel.shutdownNow();
        }
    }

    @Test
    void scoreTransaction_lowRisk_returnsApproveOverGrpc() {
        RiskScoringServiceGrpc.RiskScoringServiceBlockingStub stub = stub();
        UUID transactionId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();

        RiskScoreResponse response = stub.scoreTransaction(RiskScoreRequest.newBuilder()
                .setTransactionId(transactionId.toString())
                .setMerchantId(merchantId.toString())
                .setAmountCents(2_500)
                .setCurrency("USD")
                .setCustomerEmail("buyer@example.com")
                .setTimestampEpochMs(System.currentTimeMillis())
                .build());

        assertThat(response.getRecommendation()).isEqualTo("APPROVE");
        assertThat(response.getModelVersion()).isEqualTo("rules-v1.0.0");
        assertThat(response.getFallbackUsed()).isFalse();
        assertThat(response.getContributingFactorsList()).contains("no_risk_signals");
    }

    @Test
    void scoreTransaction_blockWorthy_returnsBlockOverGrpc() {
        RiskScoringServiceGrpc.RiskScoringServiceBlockingStub stub = stub();

        RiskScoreResponse response = stub.scoreTransaction(RiskScoreRequest.newBuilder()
                .setTransactionId(UUID.randomUUID().toString())
                .setMerchantId(UUID.randomUUID().toString())
                .setAmountCents(120_000)
                .setCurrency("USD")
                .setCustomerEmail("user@mailinator.com")
                .setTimestampEpochMs(System.currentTimeMillis())
                .build());

        assertThat(response.getRecommendation()).isEqualTo("BLOCK");
        assertThat(response.getScore()).isGreaterThanOrEqualTo(0.85);
        assertThat(response.getContributingFactorsList())
                .anyMatch(f -> f.startsWith("amount:"))
                .anyMatch(f -> f.startsWith("disposable_email:"));
    }

    private RiskScoringServiceGrpc.RiskScoringServiceBlockingStub stub() {
        if (channel == null) {
            channel = ManagedChannelBuilder.forAddress("localhost", grpcServerLifecycle.boundPort())
                    .usePlaintext()
                    .build();
        }
        return RiskScoringServiceGrpc.newBlockingStub(channel);
    }
}
