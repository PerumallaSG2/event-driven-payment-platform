package com.gopi.payments.ledger.reconciliation;

public enum MismatchKind {
    /** A journal entry's postings don't sum to zero in a currency. */
    ENTRY_UNBALANCED,
    /** An account's cached balance isn't the sum of its postings. */
    BALANCE_DRIFT,
    /** A currency's HOLDS account doesn't net to the total of its open holds. */
    HOLDS_ACCOUNT_DRIFT,
    /** payment-api says COMPLETED, but the ledger has no captured hold for it. */
    COMPLETED_NOT_CAPTURED,
    /** COMPLETED and captured, but for a different amount or currency. */
    CAPTURE_AMOUNT_MISMATCH,
    /** payment-api says FAILED, but the ledger captured the hold (the merchant was paid). */
    FAILED_BUT_CAPTURED,
    /** The payment is final in payment-api, but its hold is still open (the payer's money is stuck in HOLDS). */
    OPEN_HOLD_FOR_FINAL_PAYMENT,
    /** payment-processor has a saga stuck past its deadline. */
    SAGA_STUCK,
    /** A peer service couldn't be asked: its side wasn't checked, and the run is INCOMPLETE. */
    SOURCE_UNAVAILABLE
}
