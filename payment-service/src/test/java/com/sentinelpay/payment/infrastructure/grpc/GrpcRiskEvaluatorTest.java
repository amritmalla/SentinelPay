package com.sentinelpay.payment.infrastructure.grpc;

import com.sentinelpay.payment.application.port.RiskEvaluator;
import com.sentinelpay.proto.fraud.RiskScoreRequest;
import com.sentinelpay.proto.fraud.RiskScoreResponse;
import com.sentinelpay.proto.fraud.RiskScoringServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hermetic anti-drift contract: merchant_category / card_country / merchant_country
 * must reach the gRPC request. No Testcontainers / Spring context required.
 */
class GrpcRiskEvaluatorTest {

    private Server server;
    private ManagedChannel channel;
    private GrpcRiskEvaluator evaluator;
    private final AtomicReference<RiskScoreRequest> lastRequest = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        server = ServerBuilder.forPort(0)
                .addService(new RiskScoringServiceGrpc.RiskScoringServiceImplBase() {
                    @Override
                    public void scoreTransaction(
                            RiskScoreRequest request, StreamObserver<RiskScoreResponse> responseObserver) {
                        lastRequest.set(request);
                        responseObserver.onNext(RiskScoreResponse.newBuilder()
                                .setScore(0.1)
                                .setRecommendation("APPROVE")
                                .addContributingFactors("test-factor")
                                .setModelVersion("rules-v1")
                                .setFallbackUsed(false)
                                .build());
                        responseObserver.onCompleted();
                    }
                })
                .build()
                .start();
        channel = ManagedChannelBuilder.forAddress("localhost", server.getPort())
                .usePlaintext()
                .build();
        evaluator = new GrpcRiskEvaluator(RiskScoringServiceGrpc.newBlockingStub(channel), 5_000);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (channel != null) {
            channel.shutdownNow();
            channel.awaitTermination(2, TimeUnit.SECONDS);
        }
        if (server != null) {
            server.shutdownNow();
            server.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void evaluate_withFeatureFields_forwardsAllNineProtoFields() {
        UUID transactionId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();

        evaluator.evaluate(new RiskEvaluator.RiskInput(
                transactionId,
                merchantId,
                2_500,
                "USD",
                "buyer@example.com",
                "retail",
                "GB",
                "US"));

        RiskScoreRequest request = lastRequest.get();
        assertThat(request.getTransactionId()).isEqualTo(transactionId.toString());
        assertThat(request.getMerchantId()).isEqualTo(merchantId.toString());
        assertThat(request.getAmountCents()).isEqualTo(2_500);
        assertThat(request.getCurrency()).isEqualTo("USD");
        assertThat(request.getCustomerEmail()).isEqualTo("buyer@example.com");
        assertThat(request.getMerchantCategory()).isEqualTo("retail");
        assertThat(request.getCardCountry()).isEqualTo("GB");
        assertThat(request.getMerchantCountry()).isEqualTo("US");
        assertThat(request.getTimestampEpochMs()).isPositive();
    }

    @Test
    void evaluate_omittedFeatureFields_leavesProtoFieldsUnset() {
        evaluator.evaluate(new RiskEvaluator.RiskInput(
                UUID.randomUUID(),
                UUID.randomUUID(),
                2_500,
                "USD",
                "buyer@example.com"));

        RiskScoreRequest request = lastRequest.get();
        assertThat(request.getMerchantCategory()).isEmpty();
        assertThat(request.getCardCountry()).isEmpty();
        assertThat(request.getMerchantCountry()).isEmpty();
        assertThat(request.getTimestampEpochMs()).isPositive();
    }
}
