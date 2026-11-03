package com.gopi.payments.ledger.service;

/** A malformed journal entry or request, rejected before touching the database (HTTP 400). */
public class InvalidEntryException extends RuntimeException {
    public InvalidEntryException(String message) {
        super(message);
    }
}
