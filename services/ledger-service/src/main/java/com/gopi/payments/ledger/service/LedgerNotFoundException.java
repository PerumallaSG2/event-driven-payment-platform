package com.gopi.payments.ledger.service;

/** An account or hold that doesn't exist (HTTP 404). */
public abstract class LedgerNotFoundException extends RuntimeException {
    protected LedgerNotFoundException(String message) {
        super(message);
    }
}
