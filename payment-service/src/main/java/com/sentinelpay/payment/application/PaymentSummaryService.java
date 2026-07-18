package com.sentinelpay.payment.application;

import com.sentinelpay.common.error.ApiException;
import com.sentinelpay.common.error.ErrorCode;
import com.sentinelpay.payment.domain.PaymentStatus;
import com.sentinelpay.payment.infrastructure.persistence.PaymentAttemptEntity;
import com.sentinelpay.payment.infrastructure.persistence.PaymentAttemptRepository;
import com.sentinelpay.payment.infrastructure.persistence.PaymentEntity;
import com.sentinelpay.payment.infrastructure.persistence.PaymentRepository;
import com.sentinelpay.payment.infrastructure.rest.PaymentSummaryView;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class PaymentSummaryService {

    private static final BigDecimal REVIEW_THRESHOLD = new BigDecimal("0.30");
    private static final BigDecimal BLOCK_THRESHOLD = new BigDecimal("0.70");
    private static final Set<String> FAILURE_OUTCOMES = Set.of(
            "HARD_FAIL", "RETRYABLE", "NOT_AUTHORIZED", "AMBIGUOUS_TIMEOUT", "FAILED");

    private final PaymentRepository paymentRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;

    public PaymentSummaryService(
            PaymentRepository paymentRepository, PaymentAttemptRepository paymentAttemptRepository) {
        this.paymentRepository = paymentRepository;
        this.paymentAttemptRepository = paymentAttemptRepository;
    }

    public PaymentSummaryView getSummary(UUID paymentId, UUID merchantId) {
        PaymentEntity payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "payment_not_found"));
        MerchantOwnership.assertOwned(merchantId, payment);

        List<PaymentAttemptEntity> attempts = paymentAttemptRepository.findByPaymentId(paymentId);
        return new PaymentSummaryView(
                payment.getId(),
                payment.getStatus(),
                payment.getAmountCents(),
                payment.getCurrency(),
                payment.getCreatedAt(),
                toMerchantRisk(payment),
                toOutcome(payment, attempts));
    }

    private static PaymentSummaryView.MerchantRiskView toMerchantRisk(PaymentEntity payment) {
        if (payment.getRiskRecommendation() == null && payment.getRiskScore() == null) {
            return null;
        }
        return new PaymentSummaryView.MerchantRiskView(
                payment.getRiskRecommendation(),
                scoreBand(payment.getRiskScore(), payment.getRiskRecommendation()));
    }

    static String scoreBand(BigDecimal score, String recommendation) {
        if (score != null) {
            if (score.compareTo(BLOCK_THRESHOLD) >= 0) {
                return "HIGH";
            }
            if (score.compareTo(REVIEW_THRESHOLD) >= 0) {
                return "MEDIUM";
            }
            return "LOW";
        }
        return switch (recommendation) {
            case "BLOCK" -> "HIGH";
            case "REVIEW" -> "MEDIUM";
            default -> "LOW";
        };
    }

    private static PaymentSummaryView.OutcomeView toOutcome(
            PaymentEntity payment, List<PaymentAttemptEntity> attempts) {
        return new PaymentSummaryView.OutcomeView(
                attempts.size(),
                computeRecovered(payment.getStatus(), attempts),
                payment.getProvider());
    }

    private static boolean computeRecovered(PaymentStatus status, List<PaymentAttemptEntity> attempts) {
        if (status != PaymentStatus.COMPLETED || attempts.isEmpty()) {
            return false;
        }
        short winningAttempt = (short) attempts.stream()
                .filter(a -> "CAPTURED".equals(a.getOutcome()))
                .mapToInt(PaymentAttemptEntity::getAttemptNumber)
                .max()
                .orElse(0);
        if (winningAttempt > 1) {
            return true;
        }
        return attempts.stream()
                .anyMatch(a -> a.getAttemptNumber() <= winningAttempt && FAILURE_OUTCOMES.contains(a.getOutcome()));
    }
}
