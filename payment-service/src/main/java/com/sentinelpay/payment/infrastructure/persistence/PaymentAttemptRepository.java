package com.sentinelpay.payment.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttemptEntity, UUID> {

    List<PaymentAttemptEntity> findByPaymentId(UUID paymentId);

    Optional<PaymentAttemptEntity> findByPaymentIdAndDownstreamKey(UUID paymentId, String downstreamKey);

    Optional<PaymentAttemptEntity> findByPaymentIdAndAttemptNumber(UUID paymentId, short attemptNumber);
}
