package com.gopi.payments.processor.reconciliation;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * What counts as a stuck saga for ledger-service's reconciliation.
 *
 * @param grace              time the sweep gets past a deadline to act before the saga counts as stuck
 * @param stuckAfterAttempts a capture or release sent this many times without an answer counts as stuck
 */
@ConfigurationProperties("reconciliation")
public record ReconciliationProperties(Duration grace, int stuckAfterAttempts) {}
