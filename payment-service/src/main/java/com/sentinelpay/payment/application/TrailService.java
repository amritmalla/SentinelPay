package com.sentinelpay.payment.application;

import com.sentinelpay.common.error.ApiException;
import com.sentinelpay.common.error.ErrorCode;
import com.sentinelpay.payment.application.routing.RoutingDecisionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelpay.payment.infrastructure.persistence.PaymentAttemptEntity;
import com.sentinelpay.payment.infrastructure.persistence.PaymentAttemptRepository;
import com.sentinelpay.payment.infrastructure.persistence.PaymentEntity;
import com.sentinelpay.payment.infrastructure.persistence.PaymentRepository;
import com.sentinelpay.payment.infrastructure.rest.DecisionTrail;
import com.sentinelpay.payment.infrastructure.rest.PaymentAttemptView;
import com.sentinelpay.payment.infrastructure.rest.RoutingTrailView;
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
    private final ObjectMapper objectMapper;

    public TrailService(
            PaymentRepository paymentRepository,
            PaymentAttemptRepository paymentAttemptRepository,
            RiskAssessmentClient riskAssessmentClient,
            ObjectMapper objectMapper) {
        this.paymentRepository = paymentRepository;
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.riskAssessmentClient = riskAssessmentClient;
        this.objectMapper = objectMapper;
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
                toRoutingTrail(payment.getRoutingDecisionJson()),
                attempts);
    }

    private RoutingTrailView toRoutingTrail(String routingJson) {
        RoutingDecisionMapper.RoutingDecisionDocument doc =
                RoutingDecisionMapper.fromJson(routingJson, objectMapper);
        if (doc == null) {
            return null;
        }
        return new RoutingTrailView(
                doc.policy(), doc.orderedProviders(), doc.flags(), doc.split(), doc.rationaleByProvider());
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
