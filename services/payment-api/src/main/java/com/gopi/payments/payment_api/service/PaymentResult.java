package com.gopi.payments.payment_api.service;

import java.util.UUID;

/** What POST /payments answers: the stored status and JSON body, and whether they were replayed for a retry. */
public record PaymentResult(UUID paymentId, int httpStatus, String body, boolean replayed) {
}
