package com.gopi.payments.ledger.service;

import java.util.UUID;

public class AccountNotFoundException extends LedgerNotFoundException {
    public AccountNotFoundException(UUID id) {
        super("account " + id + " not found");
    }
}
