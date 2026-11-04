package com.gopi.payments.payment_api.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param ttl         how long a key is remembered from first use; after that the same key creates a new payment
 * @param lockTimeout how long an IN_PROGRESS claim blocks other requests before it counts as stale (crashed)
 */
@ConfigurationProperties("payments.idempotency")
public record IdempotencyProperties(Duration ttl, Duration lockTimeout) {
}
