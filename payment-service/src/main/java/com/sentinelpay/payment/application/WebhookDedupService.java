package com.sentinelpay.payment.application;

import com.sentinelpay.payment.infrastructure.persistence.ProcessedWebhookEventEntity;
import com.sentinelpay.payment.infrastructure.persistence.ProcessedWebhookEventRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class WebhookDedupService {

    private final ProcessedWebhookEventRepository processedWebhookEventRepository;

    WebhookDedupService(ProcessedWebhookEventRepository processedWebhookEventRepository) {
        this.processedWebhookEventRepository = processedWebhookEventRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    boolean recordIfNew(String eventId, String eventType) {
        if (processedWebhookEventRepository.existsById(eventId)) {
            return false;
        }
        try {
            processedWebhookEventRepository.saveAndFlush(new ProcessedWebhookEventEntity(eventId, eventType));
            return true;
        } catch (DataIntegrityViolationException ex) {
            return false;
        }
    }
}
