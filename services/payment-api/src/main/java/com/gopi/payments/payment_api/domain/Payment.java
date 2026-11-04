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

    public UUID getId() { return id; }
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
