package com.gopi.payments.payment_api.service;

/** Another request with the same key is still being processed (409; retry later). */
public class IdempotencyKeyInFlightException extends RuntimeException {
    public IdempotencyKeyInFlightException() {
        super("A request with this Idempotency-Key is still being processed");
    }
}
