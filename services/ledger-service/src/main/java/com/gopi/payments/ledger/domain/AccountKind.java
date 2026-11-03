package com.gopi.payments.ledger.domain;

public enum AccountKind {
    CUSTOMER,
    MERCHANT,
    /** Per-currency suspense account: money reserved by open holds. System account, may go negative. */
    HOLDS,
    /** Per-currency account for money entering or leaving the ledger (deposits). System account, may go negative. */
    EXTERNAL;

    public boolean isSystem() {
        return this == HOLDS || this == EXTERNAL;
    }
}
