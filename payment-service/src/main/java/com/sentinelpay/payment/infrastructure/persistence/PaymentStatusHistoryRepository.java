package com.sentinelpay.payment.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PaymentStatusHistoryRepository extends JpaRepository<PaymentStatusHistoryEntity, Long> {

    List<PaymentStatusHistoryEntity> findByPaymentIdOrderByIdAsc(UUID paymentId);
}
