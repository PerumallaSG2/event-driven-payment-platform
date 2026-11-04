package com.gopi.payments.payment_api.service;

/** A review for a payment that isn't waiting for one, or a second review that contradicts the first. */
public class PaymentNotReviewableException extends RuntimeException {
    public PaymentNotReviewableException(String message) {
        super(message);
    }
}
