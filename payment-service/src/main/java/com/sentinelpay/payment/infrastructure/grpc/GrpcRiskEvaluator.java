package com.sentinelpay.payment.infrastructure.grpc;

import com.sentinelpay.payment.application.port.RiskEvaluator;
import com.sentinelpay.proto.fraud.RiskScoreRequest;
import com.sentinelpay.proto.fraud.RiskScoreResponse;
import com.sentinelpay.proto.fraud.RiskScoringServiceGrpc;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class GrpcRiskEvaluator implements RiskEvaluator {

    private final RiskScoringServiceGrpc.RiskScoringServiceBlockingStub stub;

    public GrpcRiskEvaluator(RiskScoringServiceGrpc.RiskScoringServiceBlockingStub stub) {
        this.stub = stub;
    }

    @Override
    public RiskDecision evaluate(RiskInput input) {
        RiskScoreRequest request = RiskScoreRequest.newBuilder()
                .setTransactionId(input.transactionId().toString())
                .setMerchantId(input.merchantId().toString())
                .setAmountCents(input.amountCents())
                .setCurrency(input.currency())
                .setCustomerEmail(input.customerEmail())
                .setTimestampEpochMs(System.currentTimeMillis())
                .build();

        RiskScoreResponse response = stub.withDeadlineAfter(500, TimeUnit.MILLISECONDS)
                .scoreTransaction(request);

        return new RiskDecision(
                response.getScore(),
                response.getRecommendation(),
                response.getContributingFactorsList(),
                response.getModelVersion(),
                response.getFallbackUsed());
    }
}
