package com.gopi.payments.payment_api.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deletes rows published longer ago than {@code messaging.outbox.retention}; unpublished rows are never touched. */
@Component
public class OutboxCleanup {

    private static final Logger log = LoggerFactory.getLogger(OutboxCleanup.class);

    private final OutboxRepository outbox;
    private final OutboxProperties properties;

    public OutboxCleanup(OutboxRepository outbox, OutboxProperties properties) {
        this.outbox = outbox;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${messaging.outbox.cleanup-interval:PT24H}")
    public void deleteOldPublished() {
        int deleted = outbox.deletePublishedOlderThan(properties.retention());
        if (deleted > 0) {
            log.info("Deleted {} published outbox rows", deleted);
        }
    }
}
