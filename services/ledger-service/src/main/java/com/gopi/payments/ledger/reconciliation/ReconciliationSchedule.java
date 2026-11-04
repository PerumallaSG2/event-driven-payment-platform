package com.gopi.payments.ledger.reconciliation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the reconciliation every {@code reconciliation.interval} (15 min); off with {@code reconciliation.enabled=false}. */
@Component
@ConditionalOnProperty(name = "reconciliation.enabled", havingValue = "true", matchIfMissing = true)
public class ReconciliationSchedule {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationSchedule.class);

    private final Reconciliation reconciliation;

    public ReconciliationSchedule(Reconciliation reconciliation) {
        this.reconciliation = reconciliation;
    }

    @Scheduled(fixedDelayString = "${reconciliation.interval:PT15M}",
            initialDelayString = "${reconciliation.initial-delay:PT1M}")
    public void scheduledRun() {
        try {
            reconciliation.run();
        } catch (RuntimeException e) {   // e.g. the ledger's database is down: try again next interval
            log.error("reconciliation run failed", e);
        }
    }
}
