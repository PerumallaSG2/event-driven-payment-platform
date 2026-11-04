package com.gopi.payments.processor.fraud;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/**
 * @param baseUrl        fraud-detection, e.g. http://localhost:8080
 * @param connectTimeout per connection attempt
 * @param readTimeout    per request, at most what is left of {@code timeout}
 * @param timeout        the whole check (submit and polls); no decision by then means fail closed
 * @param pollInterval   between polls of a transaction not scored yet, and between submit attempts
 * @param submitAttempts tries of the POST while fraud-detection certainly didn't accept it (refused, 503)
 */
@ConfigurationProperties("fraud")
public record FraudProperties(URI baseUrl, Duration connectTimeout, Duration readTimeout, Duration timeout,
                              Duration pollInterval, int submitAttempts) {
}
