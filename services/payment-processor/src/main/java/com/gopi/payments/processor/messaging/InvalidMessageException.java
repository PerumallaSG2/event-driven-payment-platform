package com.gopi.payments.processor.messaging;

/** A record that can never be handled (bad JSON, missing header, unknown type): not retried, sent to the DLT. */
public class InvalidMessageException extends RuntimeException {
    public InvalidMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
