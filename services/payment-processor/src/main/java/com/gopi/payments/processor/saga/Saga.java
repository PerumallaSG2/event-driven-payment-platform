package com.gopi.payments.processor.saga;

import com.gopi.payments.processor.messaging.Messages.PaymentInitiated;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

    /** HOLD_REQUESTED after this: compensate. PENDING_REVIEW after this: compensate (REVIEW_TIMEOUT). */
    @Column(nullable = false)
    private Instant deadlineAt;

    // The card/merchant fields fraud-detection gets unchanged. Null only on sagas started before item 5.
    @Column(length = 64)
    private String cardId;

    @Column(length = 100)
    private String merchant;

    @Column(length = 50)
    private String merchantCategory;

    @Column(length = 100)
    private String city;

    private Double latitude;

    private Double longitude;

    /** The instant; with {@link #transactionOffsetSeconds} it is the payer's original timestamp. */
    private Instant transactionTime;

    private Integer transactionOffsetSeconds;

    private UUID fraudTransactionId;

    /** Null until persisted, which tells Spring Data the saga is new. */
    @Version
    @Column(nullable = false)
    private Long version;

    protected Saga() {}

    public Saga(PaymentInitiated e, Instant now, Instant deadlineAt) {
        this.paymentId = e.paymentId();
        this.payerAccountId = e.payerAccountId();
        this.merchantAccountId = e.merchantAccountId();
        this.amount = e.amount().setScale(2, RoundingMode.UNNECESSARY);
        this.currency = e.currency();
        this.cardId = e.cardId();
        this.merchant = e.merchant();
        this.merchantCategory = e.merchantCategory();
        this.city = e.city();
        this.latitude = e.latitude();
        this.longitude = e.longitude();
        this.transactionTime = e.timestamp().toInstant();
        this.transactionOffsetSeconds = e.timestamp().getOffset().getTotalSeconds();
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

    /** fraud-detection said REVIEW: the hold stays until a person decides, or until {@code reviewDeadline}. */
    void awaitReview(Instant reviewDeadline, Instant now) {
        this.deadlineAt = reviewDeadline;
        moveTo(SagaState.PENDING_REVIEW, now);
    }

    void scoredAs(UUID fraudTransactionId) {
        this.fraudTransactionId = fraudTransactionId;
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
    public String getCardId() { return cardId; }
    public String getMerchant() { return merchant; }
    public String getMerchantCategory() { return merchantCategory; }
    public String getCity() { return city; }
    public Double getLatitude() { return latitude; }
    public Double getLongitude() { return longitude; }
    public UUID getFraudTransactionId() { return fraudTransactionId; }

    /** The payer's timestamp with its original offset; null on sagas started before item 5. */
    public OffsetDateTime getTransactionTime() {
        return transactionTime == null ? null
                : transactionTime.atOffset(ZoneOffset.ofTotalSeconds(transactionOffsetSeconds));
    }
}
