package com.gopi.payments.ledger.reconciliation;

import com.gopi.payments.ledger.reconciliation.LedgerChecks.PaymentHold;
import com.gopi.payments.ledger.reconciliation.PeerClient.FinalPayment;
import com.gopi.payments.ledger.reconciliation.PeerClient.FinalPaymentsPage;
import com.gopi.payments.ledger.reconciliation.PeerClient.StuckSaga;
import com.gopi.payments.ledger.reconciliation.PeerClient.StuckSagasReport;
import com.gopi.payments.ledger.reconciliation.ReconciliationReport.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One reconciliation pass. It checks and reports; it never changes the ledger or asks another service to change
 * anything.
 * <ol>
 *   <li>The ledger's own data ({@link LedgerChecks#run}).</li>
 *   <li>Every final payment in payment-api against its ledger hold, page by page. payment-api is read first and the
 *       ledger second: the ledger settles a hold before the processor reports the outcome, and payment-api hears it
 *       last, so a payment final there already has its hold settled. A false alarm would need the ledger to be
 *       behind payment-api, which the saga's order rules out.</li>
 *   <li>payment-processor's stuck sagas.</li>
 * </ol>
 * A peer that can't be asked is a SOURCE_UNAVAILABLE mismatch and the run is INCOMPLETE; the other checks still run.
 */
@Service
public class Reconciliation {

    private static final Logger log = LoggerFactory.getLogger(Reconciliation.class);

    private final LedgerChecks ledger;
    private final PeerClient peers;
    private final ReconciliationRunRepository runs;
    private final ReconciliationProperties properties;

    public Reconciliation(LedgerChecks ledger, PeerClient peers, ReconciliationRunRepository runs,
                          ReconciliationProperties properties) {
        this.ledger = ledger;
        this.peers = peers;
        this.runs = runs;
        this.properties = properties;
    }

    public ReconciliationReport run() {
        UUID id = UUID.randomUUID();
        Instant startedAt = Instant.now();
        List<Mismatch> found = new ArrayList<>(ledger.run());
        boolean complete = true;

        int checked = 0;
        try {
            UUID after = null;
            do {
                FinalPaymentsPage page = peers.finalPayments(after, properties.pageSize());
                Map<UUID, PaymentHold> holds = ledger.holdsFor(page.payments().stream().map(FinalPayment::id).toList());
                for (FinalPayment payment : page.payments()) {
                    Mismatch m = compare(payment, holds.get(payment.id()));
                    if (m != null) {
                        found.add(m);
                    }
                }
                checked += page.payments().size();
                after = page.next();
            } while (after != null);
        } catch (RuntimeException e) {
            complete = false;
            found.add(new Mismatch(MismatchKind.SOURCE_UNAVAILABLE, "payment-api",
                    "after " + checked + " payments: " + e.getMessage()));
        }

        try {
            StuckSagasReport stuck = peers.stuckSagas();
            for (StuckSaga saga : stuck.sagas()) {
                found.add(new Mismatch(MismatchKind.SAGA_STUCK, saga.paymentId().toString(), saga.state() + ": "
                        + saga.reason() + " (updated " + saga.updatedAt() + ", deadline " + saga.deadlineAt() + ")"));
            }
            if (stuck.truncated()) {
                log.warn("payment-processor has more than {} stuck sagas; only those are reported", stuck.sagas().size());
            }
        } catch (RuntimeException e) {
            complete = false;
            found.add(new Mismatch(MismatchKind.SOURCE_UNAVAILABLE, "payment-processor", String.valueOf(e.getMessage())));
        }

        Status status = !complete ? Status.INCOMPLETE : found.isEmpty() ? Status.CLEAN : Status.MISMATCHES;
        List<Mismatch> stored = found.subList(0, Math.min(found.size(), properties.maxStoredMismatches()));
        ReconciliationReport report = new ReconciliationReport(id, status, startedAt, Instant.now(), checked,
                found.size(), List.copyOf(stored));
        runs.save(report);
        logReport(report, found);
        return report;
    }

    /** At most one mismatch per payment; null when the ledger agrees. */
    static Mismatch compare(FinalPayment payment, PaymentHold hold) {
        String subject = payment.id().toString();
        String holdStatus = hold == null ? "none" : hold.status();
        boolean completed = "COMPLETED".equals(payment.status());
        switch (holdStatus) {
            case "OPEN" -> {
                return new Mismatch(MismatchKind.OPEN_HOLD_FOR_FINAL_PAYMENT, subject,
                        payment.status() + " in payment-api, hold still OPEN for " + hold.amount() + " " + hold.currency());
            }
            case "CAPTURED" -> {
                if (!completed) {
                    return new Mismatch(MismatchKind.FAILED_BUT_CAPTURED, subject, "FAILED in payment-api ("
                            + payment.failureReason() + "), hold CAPTURED for " + hold.amount() + " " + hold.currency());
                }
                if (hold.amount().compareTo(payment.amount()) != 0 || !hold.currency().equals(payment.currency())) {
                    return new Mismatch(MismatchKind.CAPTURE_AMOUNT_MISMATCH, subject, "payment " + payment.amount()
                            + " " + payment.currency() + ", captured " + hold.amount() + " " + hold.currency());
                }
                return null;
            }
            default -> {   // no hold, or RELEASED
                return completed ? new Mismatch(MismatchKind.COMPLETED_NOT_CAPTURED, subject,
                        "COMPLETED in payment-api, hold " + holdStatus) : null;
            }
        }
    }

    private static void logReport(ReconciliationReport report, List<Mismatch> found) {
        Map<MismatchKind, Integer> byKind = new EnumMap<>(MismatchKind.class);
        found.forEach(m -> byKind.merge(m.kind(), 1, Integer::sum));
        if (report.status() == Status.CLEAN) {
            log.info("reconciliation {} CLEAN: 0 mismatches, {} payments checked", report.id(), report.paymentsChecked());
            return;
        }
        log.warn("reconciliation {} {}: {} mismatches {}, {} payments checked", report.id(), report.status(),
                report.mismatchCount(), byKind, report.paymentsChecked());
        report.mismatches().forEach(m -> log.warn("reconciliation {} {} {}: {}", report.id(), m.kind(), m.subject(),
                m.details()));
    }
}
