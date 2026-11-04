package com.gopi.payments.ledger.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Money moved from the payer to the HOLDS account, waiting to be captured (to the merchant) or released (back to
 * the payer). Each transition is its own journal entry.
 */
@Entity
@Table(name = "holds")
public class Hold {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID payerAccountId;

    @Column(nullable = false)
    private UUID merchantAccountId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private HoldStatus status;

    @Column(nullable = false)
    private UUID holdEntryId;

    /** The payment this hold belongs to; null for holds created through the REST API. */
    private UUID paymentId;

    private UUID settleEntryId;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant settledAt;

    protected Hold() {}

    public Hold(UUID paymentId, UUID payerAccountId, UUID merchantAccountId, BigDecimal amount, String currency,
                UUID holdEntryId) {
        this.id = UUID.randomUUID();
        this.paymentId = paymentId;
        this.payerAccountId = payerAccountId;
        this.merchantAccountId = merchantAccountId;
        this.amount = amount;
        this.currency = currency;
        this.status = HoldStatus.OPEN;
        this.holdEntryId = holdEntryId;
        this.createdAt = Instant.now();
    }

    /** Caller must hold the row lock and have checked the hold is OPEN. */
    public void settle(HoldStatus newStatus, UUID entryId) {
        this.status = newStatus;
        this.settleEntryId = entryId;
        this.settledAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getPayerAccountId() { return payerAccountId; }
    public UUID getMerchantAccountId() { return merchantAccountId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public HoldStatus getStatus() { return status; }
    public UUID getHoldEntryId() { return holdEntryId; }
    public UUID getPaymentId() { return paymentId; }
    public UUID getSettleEntryId() { return settleEntryId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getSettledAt() { return settledAt; }
}
