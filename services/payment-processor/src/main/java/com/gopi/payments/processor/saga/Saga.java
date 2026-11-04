package com.gopi.payments.processor.saga;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

/** One payment's saga. Callers hold the row lock and check the state before calling a transition. */
@Entity
@Table(name = "sagas")
public class Saga {

    @Id
    private UUID paymentId;

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
    private SagaState state;

    @Column(length = 50)
    private String failureReason;

    /** How many times the current command was sent. */
    @Column(nullable = false)
    private int attempts;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Column(nullable = false)
    private Instant deadlineAt;

    /** Null until persisted, which tells Spring Data the saga is new. */
    @Version
    @Column(nullable = false)
    private Long version;

    protected Saga() {}

    public Saga(UUID paymentId, UUID payerAccountId, UUID merchantAccountId, BigDecimal amount, String currency,
                Instant now, Instant deadlineAt) {
        this.paymentId = paymentId;
        this.payerAccountId = payerAccountId;
        this.merchantAccountId = merchantAccountId;
        this.amount = amount.setScale(2, RoundingMode.UNNECESSARY);
        this.currency = currency;
        this.state = SagaState.HOLD_REQUESTED;
        this.attempts = 1;
        this.createdAt = now;
        this.updatedAt = now;
        this.deadlineAt = deadlineAt;
    }

    void moveTo(SagaState next, Instant now) {
        this.state = next;
        this.attempts = 1;
        this.updatedAt = now;
    }

    /** Compensation: the reason is kept until the release is confirmed and the saga FAILS with it. */
    void compensate(String reason, Instant now) {
        this.failureReason = reason;
        moveTo(SagaState.RELEASE_REQUESTED, now);
    }

    void fail(String reason, Instant now) {
        this.failureReason = reason;
        moveTo(SagaState.FAILED, now);
    }

    void resent(Instant now) {
        this.attempts++;
        this.updatedAt = now;
    }

    public UUID getPaymentId() { return paymentId; }
    public UUID getPayerAccountId() { return payerAccountId; }
    public UUID getMerchantAccountId() { return merchantAccountId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public SagaState getState() { return state; }
    public String getFailureReason() { return failureReason; }
    public int getAttempts() { return attempts; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getDeadlineAt() { return deadlineAt; }
}
