package com.gopi.payments.ledger.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/** One balanced movement of money. Append-only (enforced by a DB trigger). */
@Entity
@Table(name = "journal_entries")
public class JournalEntry {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EntryType type;

    @Column(nullable = false)
    private Instant createdAt;

    protected JournalEntry() {}

    public JournalEntry(EntryType type) {
        this.id = UUID.randomUUID();
        this.type = type;
        this.createdAt = Timestamps.now();
    }

    public UUID getId() { return id; }
    public EntryType getType() { return type; }
    public Instant getCreatedAt() { return createdAt; }
}
