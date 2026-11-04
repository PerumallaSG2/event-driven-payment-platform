package com.gopi.payments.ledger.service;

import java.util.UUID;

/** A hold was requested for a payment whose release (void) was already processed. */
public class PaymentVoidedException extends RuntimeException {
    public PaymentVoidedException(UUID paymentId) {
        super("payment " + paymentId + " was voided before its hold arrived");
    }
}
