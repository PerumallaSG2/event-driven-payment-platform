package com.gopi.payments.payment_api.service;

/** A request that passes bean validation but is still invalid, such as payer = merchant or a bad key (400). */
public class InvalidPaymentRequestException extends RuntimeException {
    public InvalidPaymentRequestException(String message) {
        super(message);
    }
}
