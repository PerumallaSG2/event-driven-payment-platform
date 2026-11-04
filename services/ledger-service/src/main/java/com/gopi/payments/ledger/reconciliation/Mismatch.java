package com.gopi.payments.ledger.reconciliation;

/** One thing that doesn't add up. {@code subject}: an entry, account or payment id, a currency, or a service. */
public record Mismatch(MismatchKind kind, String subject, String details) {}
