package com.gopi.payments.payment_api.domain;

/**
 * CREATED until the saga reports on payment.events. PENDING_REVIEW: fraud-detection wants a person to decide
 * ({@code POST /payments/{id}/review}); the hold stays meanwhile. COMPLETED and FAILED are final.
 */
public enum PaymentStatus {
    CREATED,
    PENDING_REVIEW,
    COMPLETED,
    FAILED,
}
