package com.gopi.payments.ledger.reconciliation;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/**
 * {@code reconciliation.*}. The schedule itself ({@code enabled}, {@code interval}, {@code initial-delay}) is read by
 * {@link ReconciliationSchedule}.
 *
 * @param paymentApiUrl       payment-api, asked for its final payments
 * @param processorUrl        payment-processor, asked for its stuck sagas
 * @param pageSize            payments per request to payment-api, and per ledger holds query
 * @param maxStoredMismatches mismatches stored per run; the count stays exact
 */
@ConfigurationProperties("reconciliation")
public record ReconciliationProperties(URI paymentApiUrl, URI processorUrl, Duration connectTimeout,
                                       Duration readTimeout, int pageSize, int maxStoredMismatches) {}
