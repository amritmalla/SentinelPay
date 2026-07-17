package com.sentinelpay.risk.infrastructure.grpc;

import com.sentinelpay.proto.fraud.RiskScoreRequest;
import com.sentinelpay.proto.fraud.RiskScoreResponse;
import com.sentinelpay.proto.fraud.RiskScoringServiceGrpc;
import com.sentinelpay.risk.application.RiskAssessmentService;
import com.sentinelpay.risk.domain.scoring.RiskResult;
import com.sentinelpay.risk.domain.scoring.ScoringInput;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class RiskScoringServer extends RiskScoringServiceGrpc.RiskScoringServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(RiskScoringServer.class);

    private final RiskAssessmentService riskAssessmentService;

    public RiskScoringServer(RiskAssessmentService riskAssessmentService) {
        this.riskAssessmentService = riskAssessmentService;
    }

    @Override
    public void scoreTransaction(RiskScoreRequest request, StreamObserver<RiskScoreResponse> observer) {
        try {
            ScoringInput input = new ScoringInput(
                    UUID.fromString(request.getTransactionId()),
                    UUID.fromString(request.getMerchantId()),
                    request.getAmountCents(),
                    request.getCurrency(),
                    request.getCustomerEmail(),
                    0,
                    blankToNull(request.getMerchantCategory()),
                    blankToNull(request.getCardCountry()),
                    blankToNull(request.getMerchantCountry()),
                    request.getTimestampEpochMs() == 0 ? null : request.getTimestampEpochMs());

            RiskResult result = riskAssessmentService.assess(input);

            observer.onNext(RiskScoreResponse.newBuilder()
                    .setScore(result.score())
                    .setRecommendation(result.recommendation())
                    .addAllContributingFactors(result.factors())
                    .setModelVersion(result.modelVersion())
                    .setFallbackUsed(false)
                    .build());
            observer.onCompleted();
        } catch (IllegalArgumentException ex) {
            log.warn("Invalid risk score request: {}", ex.getMessage());
            observer.onError(Status.INVALID_ARGUMENT.withDescription(ex.getMessage()).asRuntimeException());
        } catch (Exception ex) {
            log.error("Risk scoring failed", ex);
            observer.onError(Status.INTERNAL.withDescription("Risk scoring failed").asRuntimeException());
        }
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }
}
