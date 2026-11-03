package com.gopi.payments.ledger.api;

import com.gopi.payments.ledger.domain.Hold;
import com.gopi.payments.ledger.domain.HoldStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record HoldResponse(UUID holdId, UUID payerAccountId, UUID merchantAccountId, BigDecimal amount,
                           String currency, HoldStatus status, Instant createdAt, Instant settledAt) {

    static HoldResponse from(Hold h) {
        return new HoldResponse(h.getId(), h.getPayerAccountId(), h.getMerchantAccountId(), h.getAmount(),
                h.getCurrency(), h.getStatus(), h.getCreatedAt(), h.getSettledAt());
    }
}
