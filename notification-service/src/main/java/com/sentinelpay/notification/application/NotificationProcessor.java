package com.sentinelpay.notification.application;

import com.sentinelpay.common.events.EventEnvelope;
import com.sentinelpay.notification.infrastructure.mail.MailHogMailSender;
import com.sentinelpay.notification.infrastructure.persistence.DeliveryAttemptEntity;
import com.sentinelpay.notification.infrastructure.persistence.DeliveryAttemptRepository;
import com.sentinelpay.notification.infrastructure.persistence.NotificationEntity;
import com.sentinelpay.notification.infrastructure.persistence.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class NotificationProcessor {

    private static final Logger log = LoggerFactory.getLogger(NotificationProcessor.class);
    private static final String CHANNEL_EMAIL = "EMAIL";

    private final NotificationRepository notificationRepository;
    private final DeliveryAttemptRepository deliveryAttemptRepository;
    private final NotificationEmailRenderer renderer;
    private final MailHogMailSender mailSender;

    public NotificationProcessor(
            NotificationRepository notificationRepository,
            DeliveryAttemptRepository deliveryAttemptRepository,
            NotificationEmailRenderer renderer,
            MailHogMailSender mailSender) {
        this.notificationRepository = notificationRepository;
        this.deliveryAttemptRepository = deliveryAttemptRepository;
        this.renderer = renderer;
        this.mailSender = mailSender;
    }

    @Transactional
    public void process(EventEnvelope envelope, Acknowledgment acknowledgment) {
        if (notificationRepository.countByEventId(envelope.eventId()) > 0) {
            log.debug("Duplicate eventId {} — acknowledging without reprocessing", envelope.eventId());
            acknowledgment.acknowledge();
            return;
        }

        NotificationEntity notification = new NotificationEntity();
        notification.setEventId(envelope.eventId());
        notification.setEventType(envelope.eventType());
        notification.setRecipient(renderer.resolveRecipient(envelope));
        notification.setChannel(CHANNEL_EMAIL);
        notification.setStatus("PENDING");

        try {
            notification = notificationRepository.saveAndFlush(notification);
        } catch (DataIntegrityViolationException ex) {
            log.debug("Concurrent duplicate eventId {} — acknowledging", envelope.eventId());
            acknowledgment.acknowledge();
            return;
        }

        NotificationEmailRenderer.RenderedEmail email = renderer.render(envelope);
        mailSender.send(notification.getRecipient(), email);

        DeliveryAttemptEntity attempt = new DeliveryAttemptEntity();
        attempt.setNotification(notification);
        attempt.setChannel(CHANNEL_EMAIL);
        attempt.setStatus("SENT");
        attempt.setDetail(email.subject());
        deliveryAttemptRepository.save(attempt);

        notification.setStatus("SENT");
        notificationRepository.save(notification);

        ackAfterCommit(acknowledgment);
    }

    private static void ackAfterCommit(Acknowledgment acknowledgment) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                acknowledgment.acknowledge();
            }
        });
    }
}
