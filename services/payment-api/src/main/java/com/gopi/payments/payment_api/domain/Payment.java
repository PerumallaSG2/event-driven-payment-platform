package com.gopi.payments.payment_api.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payments")
public class Payment {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(nullable = false)
    private Instant createdAt;

    protected Payment() {}

    public Payment(UUID id, BigDecimal amount, String currency) {}

    public Payment(BigDecimal amount, String currency) {
        this.amount = amount;
        this.currency = currency;
        this.status = PaymentStatus.CREATED;
        this.createdAt = Instant.now();
    }
    public UUID getId() { return id; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public PaymentStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
