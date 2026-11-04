package com.gopi.payments.payment_api.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
@Table(name = "payments")
public class Payment {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private UUID payerAccountId;

    @Column(nullable = false)
    private UUID merchantAccountId;

    @Column(nullable = false, length = 64)
    private String cardId;

    @Column(nullable = false, length = 100)
    private String merchant;

    @Column(nullable = false, length = 50)
    private String merchantCategory;

    @Column(nullable = false, length = 100)
    private String city;

    @Column(nullable = false)
    private double latitude;

    @Column(nullable = false)
    private double longitude;

    /** The instant; timestamptz keeps no offset, so it is stored separately in {@code transactionOffsetSeconds}. */
    @Column(name = "transaction_time", nullable = false)
    private Instant transactionTime;

    @Column(name = "transaction_offset_seconds", nullable = false)
    private int transactionOffsetSeconds;

    @Column(length = 50)
    private String failureReason;

    // fraud-detection's verdict, from FraudChecked; null until then (and for good if the check failed closed)
    private UUID fraudTransactionId;

    @Column(length = 10)
    private String fraudDecision;

    private Integer fraudScore;

    /** The triggered rules as fraud-detection returned them: a JSON array of {rule, points, reason}. */
    @Column(columnDefinition = "text")
    private String fraudRules;

    /** APPROVE or REJECT, once a person reviewed a payment in PENDING_REVIEW. */
    @Column(length = 10)
    private String reviewDecision;

    private Instant reviewedAt;

    protected Payment() {}

    public Payment(UUID payerAccountId, UUID merchantAccountId, BigDecimal amount, String currency, String cardId,
                   String merchant, String merchantCategory, String city, double latitude, double longitude,
                   OffsetDateTime transactionTime) {
        this.payerAccountId = payerAccountId;
        this.merchantAccountId = merchantAccountId;
        this.amount = amount.setScale(2, RoundingMode.UNNECESSARY);   // NUMERIC(19,2); the API rejects more decimals
        this.currency = currency;
        this.cardId = cardId;
        this.merchant = merchant;
        this.merchantCategory = merchantCategory;
        this.city = city;
        this.latitude = latitude;
        this.longitude = longitude;
        this.transactionTime = transactionTime.toInstant();
        this.transactionOffsetSeconds = transactionTime.getOffset().getTotalSeconds();
        this.status = PaymentStatus.CREATED;
        this.createdAt = Instant.now();
    }

    /** Records fraud-detection's verdict once; REVIEW moves a CREATED payment to PENDING_REVIEW. */
    public boolean recordFraud(UUID transactionId, String decision, int score, String rulesJson) {
        if (fraudDecision != null || isFinal()) return false;
        fraudTransactionId = transactionId;
        fraudDecision = decision;
        fraudScore = score;
        fraudRules = rulesJson;
        if ("REVIEW".equals(decision)) {
            status = PaymentStatus.PENDING_REVIEW;
        }
        return true;
    }

    /** A person's decision; the status stays PENDING_REVIEW until the saga reports the outcome. */
    public void review(boolean approve, Instant now) {
        if (status != PaymentStatus.PENDING_REVIEW || reviewDecision != null) {
            throw new IllegalStateException("payment " + id + " is not waiting for a review");
        }
        reviewDecision = approve ? "APPROVE" : "REJECT";
        reviewedAt = now;
    }

    /** Applies the saga's outcome once: final statuses never move. */
    public boolean complete() {
        if (isFinal()) return false;
        status = PaymentStatus.COMPLETED;
        return true;
    }

    public boolean fail(String reason) {
        if (isFinal()) return false;
        status = PaymentStatus.FAILED;
        failureReason = reason;
        return true;
    }

    private boolean isFinal() {
        return status == PaymentStatus.COMPLETED || status == PaymentStatus.FAILED;
    }

    public UUID getId() { return id; }
    public UUID getFraudTransactionId() { return fraudTransactionId; }
    public String getFraudDecision() { return fraudDecision; }
    public Integer getFraudScore() { return fraudScore; }
    public String getFraudRules() { return fraudRules; }
    public String getReviewDecision() { return reviewDecision; }
    public Instant getReviewedAt() { return reviewedAt; }
    public String getFailureReason() { return failureReason; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public PaymentStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public UUID getPayerAccountId() { return payerAccountId; }
    public UUID getMerchantAccountId() { return merchantAccountId; }
    public String getCardId() { return cardId; }
    public String getMerchant() { return merchant; }
    public String getMerchantCategory() { return merchantCategory; }
    public String getCity() { return city; }
    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }

    /** The transaction time in the offset it was submitted with. */
    public OffsetDateTime getTransactionTime() {
        return transactionTime.atOffset(ZoneOffset.ofTotalSeconds(transactionOffsetSeconds));
    }
}
