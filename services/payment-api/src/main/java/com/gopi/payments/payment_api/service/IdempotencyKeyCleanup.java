package com.gopi.payments.payment_api.service;

import com.gopi.payments.payment_api.repo.IdempotencyKeyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes expired keys to keep the table small. Correctness doesn't depend on it: an expired row is treated as
 * absent whether or not it was deleted yet.
 */
@Component
public class IdempotencyKeyCleanup {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyKeyCleanup.class);

    private final IdempotencyKeyRepository keys;

    public IdempotencyKeyCleanup(IdempotencyKeyRepository keys) {
        this.keys = keys;
    }

    @Scheduled(fixedDelayString = "${payments.idempotency.cleanup-interval:PT1H}")
    public void deleteExpired() {
        int deleted = keys.deleteExpired();
        if (deleted > 0) {
            log.info("Deleted {} expired idempotency keys", deleted);
        }
    }
}
