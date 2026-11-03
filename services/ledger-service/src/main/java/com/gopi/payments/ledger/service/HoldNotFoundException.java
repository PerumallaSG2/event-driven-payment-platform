package com.gopi.payments.ledger.service;

import java.util.UUID;

public class HoldNotFoundException extends LedgerNotFoundException {
    public HoldNotFoundException(UUID id) {
        super("hold " + id + " not found");
    }
}
