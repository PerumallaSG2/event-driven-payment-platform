package com.gopi.payments.processor.saga;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Finds sagas stuck waiting for the ledger (lost command, ledger down, reply in a DLT) and moves them on: re-send
 * the current command, or compensate a hold that never came. One transaction per run; the due rows stay locked
 * until it commits, so parallel sweeps (several instances) never handle the same saga twice.
 */
@Component
public class SagaSweep {

    private static final Logger log = LoggerFactory.getLogger(SagaSweep.class);

    private final DueSagas due;
    private final SagaRepository sagas;
    private final SagaService service;
    private final SagaProperties properties;
    private final TransactionTemplate tx;

    public SagaSweep(DueSagas due, SagaRepository sagas, SagaService service, SagaProperties properties,
                     TransactionTemplate tx) {
        this.due = due;
        this.sagas = sagas;
        this.service = service;
        this.properties = properties;
        this.tx = tx;
    }

    @Scheduled(fixedDelayString = "${saga.sweep-interval:PT5S}")
    public void scheduledRun() {
        try {
            sweep();
        } catch (RuntimeException e) {
            log.warn("Saga sweep failed; due sagas are picked up next run: {}", e.toString());
        }
    }

    /** Handles one batch of due sagas; returns how many. */
    public int sweep() {
        Integer handled = tx.execute(status -> {
            Instant now = Instant.now();
            List<UUID> ids = due.lock(now.minus(properties.resendAfter()), now, properties.sweepBatchSize());
            for (UUID id : ids) {
                service.sweep(sagas.findById(id).orElseThrow(), now);
            }
            return ids.size();
        });
        return handled == null ? 0 : handled;
    }
}
