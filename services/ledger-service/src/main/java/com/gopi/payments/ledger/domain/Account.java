package com.gopi.payments.ledger.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A ledger account. {@code balance} caches the sum of the account's postings; only
 * {@link com.gopi.payments.ledger.service.LedgerService} changes it, while holding the row lock.
 */
@Entity
@Table(name = "accounts")
public class Account {

    @Id
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountKind kind;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @Column(nullable = false)
    private boolean allowNegative;

    @Column(nullable = false)
    private Instant createdAt;

    protected Account() {}

    public Account(String name, AccountKind kind, String currency, boolean allowNegative) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.kind = kind;
        this.currency = currency;
        this.balance = BigDecimal.ZERO.setScale(2);
        this.allowNegative = allowNegative;
        this.createdAt = Timestamps.now();
    }

    /** Adds a signed amount; the caller must hold the row lock and check {@link #wouldOverdraw} first. */
    public void apply(BigDecimal amount) {
        this.balance = this.balance.add(amount);
    }

    public boolean wouldOverdraw(BigDecimal amount) {
        return !allowNegative && balance.add(amount).signum() < 0;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public AccountKind getKind() { return kind; }
    public String getCurrency() { return currency; }
    public BigDecimal getBalance() { return balance; }
    public boolean isAllowNegative() { return allowNegative; }
    public Instant getCreatedAt() { return createdAt; }
}
