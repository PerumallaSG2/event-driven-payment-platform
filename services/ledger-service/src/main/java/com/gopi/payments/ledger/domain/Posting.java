package com.gopi.payments.ledger.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.util.UUID;

/** One line of a journal entry. Signed: positive = money into the account. Append-only. */
@Entity
@Table(name = "postings")
public class Posting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private UUID entryId;

    @Column(nullable = false)
    private UUID accountId;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    protected Posting() {}

    public Posting(UUID entryId, UUID accountId, String currency, BigDecimal amount) {
        this.entryId = entryId;
        this.accountId = accountId;
        this.currency = currency;
        this.amount = amount;
    }

    public Long getId() { return id; }
    public UUID getEntryId() { return entryId; }
    public UUID getAccountId() { return accountId; }
    public String getCurrency() { return currency; }
    public BigDecimal getAmount() { return amount; }
}
