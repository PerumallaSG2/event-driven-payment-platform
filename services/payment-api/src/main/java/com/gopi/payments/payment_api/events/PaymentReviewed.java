package com.gopi.payments.payment_api.events;

import java.util.UUID;

/** On payment.reviews, keyed by paymentId: a person's decision on a payment fraud-detection sent to REVIEW. */
public record PaymentReviewed(UUID paymentId, boolean approve) {
}
