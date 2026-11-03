package com.gopi.payments.ledger.service;

import java.math.BigDecimal;
import java.util.UUID;

/** The entry would take an account that doesn't allow it below zero (HTTP 422). Nothing is written. */
public class InsufficientFundsException extends RuntimeException {
    public InsufficientFundsException(UUID accountId, BigDecimal balance, BigDecimal amount) {
        super("account " + accountId + " has " + balance + ", cannot apply " + amount);
    }
}
