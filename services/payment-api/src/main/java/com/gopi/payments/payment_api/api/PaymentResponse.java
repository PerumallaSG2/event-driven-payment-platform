package com.gopi.payments.payment_api.api;

import com.gopi.payments.payment_api.domain.Payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

/** GET /payments/{id}. {@code timestamp} comes back in the offset the client sent. */
public record PaymentResponse(
        UUID paymentId,
        String status,
        UUID payerAccountId,
        UUID merchantAccountId,
        BigDecimal amount,
        String currency,
        String cardId,
        String merchant,
        String merchantCategory,
        String city,
        double latitude,
        double longitude,
        OffsetDateTime timestamp,
        Instant createdAt) {

    public static PaymentResponse from(Payment p) {
        return new PaymentResponse(p.getId(), p.getStatus().name(), p.getPayerAccountId(), p.getMerchantAccountId(),
                p.getAmount(), p.getCurrency(), p.getCardId(), p.getMerchant(), p.getMerchantCategory(), p.getCity(),
                p.getLatitude(), p.getLongitude(), p.getTransactionTime(), p.getCreatedAt());
    }
}
