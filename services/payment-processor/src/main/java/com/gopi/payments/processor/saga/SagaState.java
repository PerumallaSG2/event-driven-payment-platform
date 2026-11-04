package com.gopi.payments.processor.saga;

public enum SagaState {
    HOLD_REQUESTED,
    CAPTURE_REQUESTED,
    RELEASE_REQUESTED,
    COMPLETED,
    FAILED;

    public boolean waitingForLedger() {
        return this == HOLD_REQUESTED || this == CAPTURE_REQUESTED || this == RELEASE_REQUESTED;
    }
}
