package com.gopi.payments.payment_api.events;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * On payment.initiated, keyed by paymentId: everything payment-processor needs to run the saga, including the
 * card/merchant fields it passes to fraud-detection unchanged ({@code timestamp} in its original offset).
 */
public record PaymentInitiatedEvent(
        UUID paymentId,
        BigDecimal amount,
        String currency,
        UUID payerAccountId,
        UUID merchantAccountId,
        String cardId,
        String merchant,
        String merchantCategory,
        String city,
        double latitude,
        double longitude,
        OffsetDateTime timestamp
) { }
