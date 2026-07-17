package com.sentinelpay.payment.application;

import com.sentinelpay.common.error.ApiException;
import com.sentinelpay.common.error.ErrorCode;
import com.sentinelpay.payment.PaymentTestContainers;
import com.sentinelpay.payment.domain.PaymentStatus;
import com.sentinelpay.payment.infrastructure.persistence.IdempotencyKeyRepository;
import com.sentinelpay.payment.infrastructure.persistence.PaymentAttemptRepository;
import com.sentinelpay.payment.infrastructure.persistence.PaymentEntity;
import com.sentinelpay.payment.infrastructure.persistence.PaymentRepository;
import com.sentinelpay.payment.infrastructure.persistence.PaymentStatusHistoryRepository;
import com.sentinelpay.payment.infrastructure.persistence.RefundRepository;
import com.sentinelpay.payment.infrastructure.rest.PaymentPage;
import com.sentinelpay.payment.infrastructure.rest.PaymentView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class PaymentQueryServiceIT {

    @Autowired
    PaymentQueryService paymentQueryService;

    @Autowired
    PaymentRepository paymentRepository;

    @Autowired
    PaymentAttemptRepository paymentAttemptRepository;

    @Autowired
    PaymentStatusHistoryRepository paymentStatusHistoryRepository;

    @Autowired
    IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    RefundRepository refundRepository;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PaymentTestContainers.register(registry);
    }

    @BeforeEach
    void clean() {
        refundRepository.deleteAll();
        paymentAttemptRepository.deleteAll();
        paymentStatusHistoryRepository.deleteAll();
        idempotencyKeyRepository.deleteAll();
        paymentRepository.deleteAll();
    }

    @Test
    void getPayment_existing_returnsMappedView() {
        PaymentEntity payment = seedPayment(UUID.randomUUID(), 2_500);

        PaymentView view = paymentQueryService.getPayment(payment.getId(), payment.getMerchantId());

        assertThat(view.paymentId()).isEqualTo(payment.getId());
        assertThat(view.merchantId()).isEqualTo(payment.getMerchantId());
        assertThat(view.status()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(view.amountCents()).isEqualTo(2_500);
        assertThat(view.trailId()).isEqualTo(payment.getId());
    }

    @Test
    void getPayment_wrongMerchant_returns404() {
        PaymentEntity payment = seedPayment(UUID.randomUUID(), 2_500);
        UUID otherMerchant = UUID.randomUUID();

        assertThatThrownBy(() -> paymentQueryService.getPayment(payment.getId(), otherMerchant))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void getPayment_missing_returns404() {
        assertThatThrownBy(() -> paymentQueryService.getPayment(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void listPayments_cursorPagination_returnsNonOverlappingPages() {
        UUID merchantId = UUID.randomUUID();
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(seedPayment(merchantId, 1_000 + i).getId());
        }

        PaymentPage first = paymentQueryService.listPayments(merchantId, null, 2);
        assertThat(first.data()).hasSize(2);
        assertThat(first.page().nextCursor()).isNotNull();

        PaymentPage second = paymentQueryService.listPayments(merchantId, first.page().nextCursor(), 2);
        assertThat(second.data()).hasSize(2);

        List<UUID> pageOneIds = first.data().stream().map(PaymentView::paymentId).toList();
        List<UUID> pageTwoIds = second.data().stream().map(PaymentView::paymentId).toList();
        assertThat(pageOneIds).doesNotContainAnyElementsOf(pageTwoIds);
        assertThat(pageOneIds).allMatch(ids::contains);
        assertThat(pageTwoIds).allMatch(ids::contains);
    }

    private PaymentEntity seedPayment(UUID merchantId, long amountCents) {
        PaymentEntity payment = new PaymentEntity();
        payment.setMerchantId(merchantId);
        payment.setStatus(PaymentStatus.COMPLETED);
        payment.setAmountCents(amountCents);
        payment.setCurrency("USD");
        payment.setProvider("mockpay");
        return paymentRepository.saveAndFlush(payment);
    }
}
