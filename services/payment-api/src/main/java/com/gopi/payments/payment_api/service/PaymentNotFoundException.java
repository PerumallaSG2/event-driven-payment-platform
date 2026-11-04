package com.gopi.payments.payment_api.service;

import java.util.UUID;

public class PaymentNotFoundException extends RuntimeException {
    public PaymentNotFoundException(UUID id) {
        super("No payment with id " + id);
    }
}
