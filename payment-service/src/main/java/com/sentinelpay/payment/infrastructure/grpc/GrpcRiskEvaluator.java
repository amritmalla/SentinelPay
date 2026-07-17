package com.sentinelpay.payment.infrastructure.grpc;

import com.sentinelpay.payment.application.RiskFallback;
import com.sentinelpay.payment.application.port.RiskEvaluator;
import com.sentinelpay.proto.fraud.RiskScoreRequest;
import com.sentinelpay.proto.fraud.RiskScoreResponse;
import com.sentinelpay.proto.fraud.RiskScoringServiceGrpc;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class GrpcRiskEvaluator implements RiskEvaluator {

    private final RiskScoringServiceGrpc.RiskScoringServiceBlockingStub stub;
    private final long deadlineMs;

    public GrpcRiskEvaluator(
            RiskScoringServiceGrpc.RiskScoringServiceBlockingStub stub,
            @Value("${sentinelpay.risk.grpc.deadline-ms:100}") long deadlineMs) {
        this.stub = stub;
        this.deadlineMs = deadlineMs;
    }

    @Override
    public RiskDecision evaluate(RiskInput input) {
        RiskScoreRequest.Builder builder = RiskScoreRequest.newBuilder()
                .setTransactionId(input.transactionId().toString())
                .setMerchantId(input.merchantId().toString())
                .setAmountCents(input.amountCents())
                .setCurrency(input.currency())
                .setCustomerEmail(input.customerEmail())
                .setTimestampEpochMs(System.currentTimeMillis());

        if (input.merchantCategory() != null && !input.merchantCategory().isBlank()) {
            builder.setMerchantCategory(input.merchantCategory());
        }
        if (input.cardCountry() != null && !input.cardCountry().isBlank()) {
            builder.setCardCountry(input.cardCountry());
        }
        if (input.merchantCountry() != null && !input.merchantCountry().isBlank()) {
            builder.setMerchantCountry(input.merchantCountry());
        }

        RiskScoreRequest request = builder.build();

        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                RiskScoreResponse response = stub.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
                        .scoreTransaction(request);
                return map(response);
            } catch (StatusRuntimeException ex) {
                if (isRetryable(ex) && attempt == 1) {
                    continue;
                }
                return RiskFallback.forAmount(input.amountCents());
            }
        }
        return RiskFallback.forAmount(input.amountCents());
    }

    private static boolean isRetryable(StatusRuntimeException ex) {
        Status.Code code = ex.getStatus().getCode();
        return code == Status.Code.UNAVAILABLE || code == Status.Code.DEADLINE_EXCEEDED;
    }

    private static RiskDecision map(RiskScoreResponse response) {
        return new RiskDecision(
                response.getScore(),
                response.getRecommendation(),
                response.getContributingFactorsList(),
                response.getModelVersion(),
                response.getFallbackUsed());
    }
}
