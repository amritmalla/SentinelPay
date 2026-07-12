package com.sentinelpay.risk.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RiskAssessmentRepository extends JpaRepository<RiskAssessmentEntity, UUID> {

    Optional<RiskAssessmentEntity> findByTransactionId(UUID transactionId);
}
