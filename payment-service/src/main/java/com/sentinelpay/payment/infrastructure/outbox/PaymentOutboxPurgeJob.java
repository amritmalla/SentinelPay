package com.sentinelpay.payment.infrastructure.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
public class PaymentOutboxPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(PaymentOutboxPurgeJob.class);

    private final PaymentOutboxRepository repository;

    public PaymentOutboxPurgeJob(PaymentOutboxRepository repository) {
        this.repository = repository;
    }

    @Scheduled(cron = "${sentinelpay.outbox.purge.cron:0 0 3 * * *}")
    @Transactional
    public void purge() {
        Instant cutoff = Instant.now().minus(7, ChronoUnit.DAYS);
        int deleted = repository.deletePublishedBefore(cutoff);
        if (deleted > 0) {
            log.info("Purged {} published payment_outbox rows older than {}", deleted, cutoff);
        }
    }
}
