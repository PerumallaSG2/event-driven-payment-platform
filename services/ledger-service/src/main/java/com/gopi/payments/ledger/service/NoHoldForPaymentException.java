package com.gopi.payments.ledger.service;

import java.util.UUID;

public class NoHoldForPaymentException extends LedgerNotFoundException {
    public NoHoldForPaymentException(UUID paymentId) {
        super("no hold for payment " + paymentId);
    }
}
