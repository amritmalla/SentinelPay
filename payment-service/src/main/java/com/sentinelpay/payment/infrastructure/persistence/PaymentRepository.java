package com.sentinelpay.payment.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<PaymentEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentEntity p where p.id = :id")
    Optional<PaymentEntity> findByIdForUpdate(@Param("id") UUID id);

    @Query("select p.id from PaymentEntity p where p.status = com.sentinelpay.payment.domain.PaymentStatus.AUTHORIZING "
            + "and p.updatedAt < :cutoff")
    List<UUID> findStuckAuthorizing(@Param("cutoff") Instant cutoff);
}
