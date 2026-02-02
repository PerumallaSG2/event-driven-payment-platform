package com.gopi.payments.payment_api.events;

import java.math.BigDecimal;
import java.util.UUID;

public record PaymentInitiatedEvent(
        UUID paymentId,
        BigDecimal amount,
        String currency
) { }
