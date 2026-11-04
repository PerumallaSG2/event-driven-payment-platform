package com.gopi.payments.processor.saga;

public enum SagaState {
    HOLD_REQUESTED,
    /** Held; fraud-detection is being asked. Never re-sent: a stale check fails closed. */
    FRAUD_CHECK,
    /** Held; fraud-detection said REVIEW, a person decides (or the review deadline passes). */
    PENDING_REVIEW,
    CAPTURE_REQUESTED,
    RELEASE_REQUESTED,
    COMPLETED,
    FAILED;

    public boolean waitingForLedger() {
        return this == HOLD_REQUESTED || this == CAPTURE_REQUESTED || this == RELEASE_REQUESTED;
    }
}
