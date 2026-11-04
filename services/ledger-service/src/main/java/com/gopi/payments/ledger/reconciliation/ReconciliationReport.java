package com.gopi.payments.ledger.reconciliation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A reconciliation run. {@code mismatchCount} is exact; {@code mismatches} holds at most
 * {@code reconciliation.max-stored-mismatches} of them.
 */
public record ReconciliationReport(UUID id, Status status, Instant startedAt, Instant finishedAt,
                                   int paymentsChecked, int mismatchCount, List<Mismatch> mismatches) {

    public enum Status { CLEAN, MISMATCHES, INCOMPLETE }
}
