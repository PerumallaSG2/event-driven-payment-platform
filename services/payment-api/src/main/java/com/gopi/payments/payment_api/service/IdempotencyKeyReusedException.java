package com.gopi.payments.payment_api.service;

/** The key was already used with a different request body (422). */
public class IdempotencyKeyReusedException extends RuntimeException {
    public IdempotencyKeyReusedException() {
        super("This Idempotency-Key was already used with a different request");
    }
}
