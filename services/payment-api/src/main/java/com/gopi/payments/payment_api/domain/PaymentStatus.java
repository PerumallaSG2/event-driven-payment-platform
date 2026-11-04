package com.gopi.payments.payment_api.domain;

/** CREATED until the saga reports its outcome on payment.events; COMPLETED and FAILED are final. */
public enum PaymentStatus {
    CREATED,
    COMPLETED,
    FAILED,
}
