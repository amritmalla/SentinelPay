package com.sentinelpay.payment.application;

import com.sentinelpay.common.error.ApiException;
import com.sentinelpay.common.error.ErrorCode;
import com.sentinelpay.payment.domain.PaymentStatus;
import com.sentinelpay.payment.infrastructure.persistence.PaymentEntity;
import com.sentinelpay.payment.infrastructure.persistence.PaymentRepository;
import com.sentinelpay.payment.infrastructure.rest.PaymentPage;
import com.sentinelpay.payment.infrastructure.rest.PaymentView;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
public class PaymentQueryService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final PaymentRepository paymentRepository;

    public PaymentQueryService(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    public PaymentView getPayment(UUID paymentId) {
        return paymentRepository.findById(paymentId)
                .map(this::toView)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "payment_not_found"));
    }

    public PaymentPage listPayments(UUID merchantId, String cursor, Integer limit) {
        int pageSize = normalizeLimit(limit);
        CursorPosition position = decodeCursor(cursor);

        List<PaymentEntity> rows;
        if (position == null) {
            rows = paymentRepository.findByMerchantIdOrderByCreatedAtDescIdDesc(
                    merchantId, PageRequest.of(0, pageSize + 1));
        } else {
            rows = paymentRepository.pageByMerchant(
                    merchantId,
                    position.createdAt(),
                    position.id(),
                    PageRequest.of(0, pageSize + 1));
        }

        String nextCursor = null;
        if (rows.size() > pageSize) {
            PaymentEntity last = rows.get(pageSize - 1);
            nextCursor = encodeCursor(last.getCreatedAt(), last.getId());
            rows = rows.subList(0, pageSize);
        }

        List<PaymentView> data = rows.stream().map(this::toView).toList();
        return new PaymentPage(data, new PaymentPage.PageInfo(nextCursor, pageSize));
    }

    private PaymentView toView(PaymentEntity payment) {
        PaymentView.RiskSummaryView risk = null;
        if (payment.getRiskScore() != null) {
            risk = new PaymentView.RiskSummaryView(
                    payment.getRiskScore(),
                    payment.getRiskRecommendation(),
                    payment.getRiskModelVersion(),
                    payment.getRiskFallbackUsed());
        }

        return new PaymentView(
                payment.getId(),
                payment.getMerchantId(),
                payment.getStatus(),
                payment.getAmountCents(),
                payment.getCurrency(),
                payment.getProvider(),
                risk,
                payment.getId(),
                payment.getFailureReason(),
                payment.getCreatedAt());
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    static String encodeCursor(Instant createdAt, UUID id) {
        String raw = createdAt.toEpochMilli() + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static CursorPosition decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separator = raw.indexOf(':');
            if (separator < 0) {
                throw new IllegalArgumentException("Invalid cursor");
            }
            Instant createdAt = Instant.ofEpochMilli(Long.parseLong(raw.substring(0, separator)));
            UUID id = UUID.fromString(raw.substring(separator + 1));
            return new CursorPosition(createdAt, id);
        } catch (RuntimeException ex) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "invalid_cursor");
        }
    }

    record CursorPosition(Instant createdAt, UUID id) {
    }
}
