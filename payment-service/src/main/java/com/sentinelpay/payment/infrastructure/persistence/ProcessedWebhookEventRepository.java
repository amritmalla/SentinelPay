package com.sentinelpay.payment.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedWebhookEventRepository extends JpaRepository<ProcessedWebhookEventEntity, String> {
}
