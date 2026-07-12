package com.sentinelpay.risk.infrastructure.grpc;

import com.sentinelpay.proto.fraud.RiskScoreRequest;
import com.sentinelpay.proto.fraud.RiskScoreResponse;
import com.sentinelpay.risk.application.RiskAssessmentService;
import com.sentinelpay.risk.domain.scoring.RiskResult;
import com.sentinelpay.risk.domain.scoring.ScoringInput;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RiskScoringServerTest {

    @Mock
    RiskAssessmentService riskAssessmentService;

    @InjectMocks
    RiskScoringServer server;

    @Test
    void scoreTransaction_mapsAssessmentResultToResponse() {
        UUID transactionId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        when(riskAssessmentService.assess(any(ScoringInput.class)))
                .thenReturn(new RiskResult(0.85, "BLOCK", List.of("amount:120000"), "rules-v1.0.0"));

        CapturingObserver observer = new CapturingObserver();
        server.scoreTransaction(RiskScoreRequest.newBuilder()
                .setTransactionId(transactionId.toString())
                .setMerchantId(merchantId.toString())
                .setAmountCents(120_000)
                .setCurrency("USD")
                .setCustomerEmail("user@mailinator.com")
                .build(), observer);

        assertThat(observer.responses).hasSize(1);
        RiskScoreResponse response = observer.responses.get(0);
        assertThat(response.getRecommendation()).isEqualTo("BLOCK");
        assertThat(response.getScore()).isEqualTo(0.85);
        assertThat(response.getModelVersion()).isEqualTo("rules-v1.0.0");
        assertThat(response.getContributingFactorsList()).containsExactly("amount:120000");
        assertThat(response.getFallbackUsed()).isFalse();
        assertThat(observer.completed).isTrue();
    }

    private static final class CapturingObserver implements StreamObserver<RiskScoreResponse> {

        private final java.util.List<RiskScoreResponse> responses = new java.util.ArrayList<>();
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
