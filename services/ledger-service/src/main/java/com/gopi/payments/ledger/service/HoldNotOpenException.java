package com.gopi.payments.ledger.service;

import com.gopi.payments.ledger.domain.HoldStatus;

import java.util.UUID;

/** Capture or release of a hold that was already settled (HTTP 409). */
public class HoldNotOpenException extends RuntimeException {
    public HoldNotOpenException(UUID holdId, HoldStatus status) {
        super("hold " + holdId + " is " + status + ", not OPEN");
    }
}
