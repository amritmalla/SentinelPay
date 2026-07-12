package com.sentinelpay.notification.infrastructure.kafka;

import com.sentinelpay.common.events.EventEnvelope;
import com.sentinelpay.notification.application.NotificationProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class PaymentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);

    private final NotificationProcessor processor;

    public PaymentEventConsumer(NotificationProcessor processor) {
        this.processor = processor;
    }

    @KafkaListener(
            topics = {
                "payment.completed",
                "payment.failed",
                "payment.refunded",
                "fraud.alert.high"
            },
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "eventKafkaListenerContainerFactory")
    public void onEvent(EventEnvelope envelope, Acknowledgment acknowledgment) {
        log.debug(
                "Received {} eventId={} correlationId={}",
                envelope.eventType(),
                envelope.eventId(),
                envelope.correlationId());
        processor.process(envelope, acknowledgment);
    }
}
