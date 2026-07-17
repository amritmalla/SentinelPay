package com.sentinelpay.payment.infrastructure.grpc;

import com.sentinelpay.payment.PaymentTestContainers;
import com.sentinelpay.payment.application.port.RiskEvaluator;
import com.sentinelpay.proto.fraud.RiskScoreRequest;
import com.sentinelpay.proto.fraud.RiskScoreResponse;
import com.sentinelpay.proto.fraud.RiskScoringServiceGrpc;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GrpcRiskEvaluatorIT {

    private static Server server;
    private static int port;
    private static final AtomicReference<RiskScoreRequest> LAST_REQUEST = new AtomicReference<>();

    @Autowired
    GrpcRiskEvaluator evaluator;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("sentinelpay.risk.grpc.target", () -> "localhost:" + port);
        // Generous deadline: the JaCoCo agent instruments gRPC/netty, so the cold first call is slow.
        // This test asserts the success path, not latency; the tight production deadline is unaffected.
        registry.add("sentinelpay.risk.grpc.deadline-ms", () -> "5000");
        registry.add("spring.datasource.url", PaymentTestContainers.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", PaymentTestContainers.POSTGRES::getUsername);
        registry.add("spring.datasource.password", PaymentTestContainers.POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", PaymentTestContainers.KAFKA::getBootstrapServers);
    }

    static {
        try {
            server = ServerBuilder.forPort(0)
                    .addService(new RiskScoringServiceGrpc.RiskScoringServiceImplBase() {
                        @Override
                        public void scoreTransaction(
                                RiskScoreRequest request, StreamObserver<RiskScoreResponse> responseObserver) {
                            LAST_REQUEST.set(request);
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
            port = server.getPort();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to start test gRPC server", ex);
        }
    }

    @AfterAll
    static void shutdownServer() {
        if (server != null) {
            server.shutdownNow();
        }
    }

    @Test
    @Order(1)
    void evaluate_serverUp_returnsApproveWithoutFallback() {
        RiskEvaluator.RiskDecision decision = evaluator.evaluate(riskInput(2_500));

        assertThat(decision.recommendation()).isEqualTo("APPROVE");
        assertThat(decision.fallbackUsed()).isFalse();
        assertThat(decision.modelVersion()).isEqualTo("rules-v1");
        assertThat(decision.factors()).contains("test-factor");
    }

    @Test
    @Order(2)
    void evaluate_withFeatureFields_forwardsAllNineProtoFields() {
        UUID transactionId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        RiskEvaluator.RiskInput input = new RiskEvaluator.RiskInput(
                transactionId,
                merchantId,
                2_500,
                "USD",
                "buyer@example.com",
                "retail",
                "GB",
                "US");

        evaluator.evaluate(input);

        RiskScoreRequest request = LAST_REQUEST.get();
        assertThat(request).isNotNull();
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
    @Order(3)
    void evaluate_omittedFeatureFields_leavesProtoFieldsUnset() {
        evaluator.evaluate(riskInput(2_500));

        RiskScoreRequest request = LAST_REQUEST.get();
        assertThat(request).isNotNull();
        assertThat(request.getMerchantCategory()).isEmpty();
        assertThat(request.getCardCountry()).isEmpty();
        assertThat(request.getMerchantCountry()).isEmpty();
        assertThat(request.getTimestampEpochMs()).isPositive();
    }

    @Test
    @Order(4)
    void evaluate_serverDown_returnsAmountBasedFallback() {
        server.shutdownNow();

        RiskEvaluator.RiskDecision lowAmount = evaluator.evaluate(riskInput(2_500));
        assertThat(lowAmount.fallbackUsed()).isTrue();
        assertThat(lowAmount.modelVersion()).isEqualTo("fallback-v0");
        assertThat(lowAmount.recommendation()).isEqualTo("APPROVE");
        assertThat(lowAmount.factors()).containsExactly("fallback:amount");

        RiskEvaluator.RiskDecision highAmount = evaluator.evaluate(riskInput(60_000));
        assertThat(highAmount.fallbackUsed()).isTrue();
        assertThat(highAmount.recommendation()).isEqualTo("REVIEW");
    }

    private static RiskEvaluator.RiskInput riskInput(long amountCents) {
        return new RiskEvaluator.RiskInput(
                UUID.randomUUID(),
                UUID.randomUUID(),
                amountCents,
                "USD",
                "buyer@example.com");
    }
}
