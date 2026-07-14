package com.sentinelpay.payment.application;

import com.sentinelpay.common.error.ApiException;
import com.sentinelpay.common.error.ErrorCode;
import com.sentinelpay.payment.infrastructure.persistence.PaymentAttemptEntity;
import com.sentinelpay.payment.infrastructure.persistence.PaymentAttemptRepository;
import com.sentinelpay.payment.infrastructure.persistence.PaymentEntity;
import com.sentinelpay.payment.infrastructure.persistence.PaymentRepository;
import com.sentinelpay.payment.infrastructure.rest.DecisionTrail;
import com.sentinelpay.payment.infrastructure.rest.PaymentAttemptView;
import com.sentinelpay.payment.infrastructure.risk.RiskAssessmentClient;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class TrailService {

    private final PaymentRepository paymentRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final RiskAssessmentClient riskAssessmentClient;

    public TrailService(
            PaymentRepository paymentRepository,
            PaymentAttemptRepository paymentAttemptRepository,
            RiskAssessmentClient riskAssessmentClient) {
        this.paymentRepository = paymentRepository;
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.riskAssessmentClient = riskAssessmentClient;
    }

    public DecisionTrail trail(UUID paymentId) {
        PaymentEntity payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "payment_not_found"));

        List<PaymentAttemptView> attempts = paymentAttemptRepository.findByPaymentId(paymentId).stream()
                .sorted(Comparator.comparing(PaymentAttemptEntity::getAttemptNumber))
                .map(this::toAttemptView)
                .toList();

        return new DecisionTrail(
                payment.getId(),
                payment.getStatus(),
                riskAssessmentClient.fetch(paymentId, payment.getMerchantId()).orElse(null),
                attempts);
    }

    private PaymentAttemptView toAttemptView(PaymentAttemptEntity attempt) {
        return new PaymentAttemptView(
                attempt.getAttemptNumber(),
                attempt.getProvider(),
                attempt.getOutcome(),
                attempt.getProviderRef(),
                attempt.getLatencyMs());
    }
}
