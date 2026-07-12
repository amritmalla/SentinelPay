package com.sentinelpay.risk.infrastructure.grpc;

import com.sentinelpay.proto.fraud.RiskScoreRequest;
import com.sentinelpay.proto.fraud.RiskScoreResponse;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RiskScoringServerTest {

    @Test
    void scoreTransaction_returnsFixedApprove() {
        RiskScoringServer server = new RiskScoringServer();
        CapturingObserver observer = new CapturingObserver();

        server.scoreTransaction(RiskScoreRequest.newBuilder()
                .setTransactionId("txn-1")
                .setMerchantId("merchant-1")
                .setAmountCents(2500)
                .setCurrency("USD")
                .setCustomerEmail("buyer@example.com")
                .build(), observer);

        assertThat(observer.responses).hasSize(1);
        RiskScoreResponse response = observer.responses.get(0);
        assertThat(response.getRecommendation()).isEqualTo("APPROVE");
        assertThat(response.getScore()).isEqualTo(0.0);
        assertThat(response.getModelVersion()).isEqualTo("skeleton-v0");
        assertThat(response.getFallbackUsed()).isFalse();
        assertThat(observer.completed).isTrue();
    }

    private static final class CapturingObserver implements StreamObserver<RiskScoreResponse> {

        private final List<RiskScoreResponse> responses = new ArrayList<>();
        private boolean completed;

        @Override
        public void onNext(RiskScoreResponse value) {
            responses.add(value);
        }

        @Override
        public void onError(Throwable t) {
            throw new AssertionError("Unexpected error", t);
        }

        @Override
        public void onCompleted() {
            completed = true;
        }
    }
}
