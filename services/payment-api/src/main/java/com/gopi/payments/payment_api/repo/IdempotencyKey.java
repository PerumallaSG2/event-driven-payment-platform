package com.gopi.payments.payment_api.repo;

import java.util.UUID;

/**
 * A row of {@code idempotency_keys}. {@code expired} and {@code lockExpired} are computed by Postgres with
 * {@code now()}, so every instance judges them with the same clock.
 */
public record IdempotencyKey(String key, String requestHash, String status, UUID claimToken, UUID paymentId,
                             Integer responseStatus, String responseBody, boolean expired, boolean lockExpired) {

    public boolean completed() {
        return "COMPLETED".equals(status);
    }
}
