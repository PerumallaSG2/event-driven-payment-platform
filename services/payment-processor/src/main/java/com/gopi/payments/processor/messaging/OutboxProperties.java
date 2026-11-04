package com.gopi.payments.processor.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param batchSize   rows the relay publishes per run
 * @param sendTimeout how long the relay waits for the broker to acknowledge a batch
 * @param retention   how long published rows are kept before cleanup deletes them
 */
@ConfigurationProperties("messaging.outbox")
public record OutboxProperties(int batchSize, Duration sendTimeout, Duration retention) {
}
