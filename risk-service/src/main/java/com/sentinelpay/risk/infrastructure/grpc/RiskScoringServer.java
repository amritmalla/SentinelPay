package com.sentinelpay.risk.infrastructure.grpc;

import com.sentinelpay.proto.fraud.RiskScoreRequest;
import com.sentinelpay.proto.fraud.RiskScoreResponse;
import com.sentinelpay.proto.fraud.RiskScoringServiceGrpc;
import io.grpc.stub.StreamObserver;

public class RiskScoringServer extends RiskScoringServiceGrpc.RiskScoringServiceImplBase {

    @Override
    public void scoreTransaction(RiskScoreRequest request, StreamObserver<RiskScoreResponse> observer) {
        observer.onNext(RiskScoreResponse.newBuilder()
                .setScore(0.0)
                .setRecommendation("APPROVE")
                .setModelVersion("skeleton-v0")
                .setFallbackUsed(false)
                .build());
        observer.onCompleted();
    }
}
