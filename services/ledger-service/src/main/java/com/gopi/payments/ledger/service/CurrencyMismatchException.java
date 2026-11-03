package com.gopi.payments.ledger.service;

import java.util.UUID;

/** A posting or hold in a different currency than the account (HTTP 422). */
public class CurrencyMismatchException extends RuntimeException {
    public CurrencyMismatchException(UUID accountId, String accountCurrency, String requested) {
        super("account " + accountId + " is in " + accountCurrency + ", not " + requested);
    }
}
