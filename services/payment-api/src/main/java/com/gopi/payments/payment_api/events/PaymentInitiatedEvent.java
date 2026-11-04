package com.gopi.payments.payment_api.events;

import java.math.BigDecimal;
import java.util.UUID;

/** On payment.initiated, keyed by paymentId: everything payment-processor needs to start the saga. */
public record PaymentInitiatedEvent(
        UUID paymentId,
        BigDecimal amount,
        String currency,
        UUID payerAccountId,
        UUID merchantAccountId
) { }
