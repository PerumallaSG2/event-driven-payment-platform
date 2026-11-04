package com.gopi.payments.processor.saga;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param resendAfter    a saga waiting this long for the ledger gets its current command sent again
 * @param holdDeadline   a saga still waiting for its hold this long after it started is compensated
 * @param sweepBatchSize sagas the sweep handles per run
 */
@ConfigurationProperties("saga")
public record SagaProperties(Duration resendAfter, Duration holdDeadline, int sweepBatchSize) {
}
