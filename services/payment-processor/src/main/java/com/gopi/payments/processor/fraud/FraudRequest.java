package com.gopi.payments.processor.fraud;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * The body of fraud-detection's {@code POST /api/transactions}: the payment's card/merchant fields, unchanged. The
 * timestamp keeps the payer's original UTC offset (fraud-detection's odd-hours rule reads the local time).
 */
public record FraudRequest(String cardId, BigDecimal amount, String merchant, String merchantCategory, String city,
                           double latitude, double longitude, OffsetDateTime timestamp) {
}
