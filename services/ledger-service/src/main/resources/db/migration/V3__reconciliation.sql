-- Item 6: reconciliation reports. The job only reads the ledger (and asks payment-api and payment-processor over
-- HTTP); it never fixes anything. Each run and what it found are kept here.
CREATE TABLE reconciliation_runs (
    id               UUID        PRIMARY KEY,
    started_at       TIMESTAMPTZ NOT NULL,
    finished_at      TIMESTAMPTZ NOT NULL,
    -- INCOMPLETE: a peer service couldn't be asked, so its side wasn't checked
    status           VARCHAR(20) NOT NULL CHECK (status IN ('CLEAN', 'MISMATCHES', 'INCOMPLETE')),
    mismatch_count   INT         NOT NULL CHECK (mismatch_count >= 0),   -- exact, even when not all are stored
    payments_checked INT         NOT NULL CHECK (payments_checked >= 0),
    CONSTRAINT reconciliation_runs_clean_has_none CHECK ((status = 'CLEAN') = (mismatch_count = 0) OR status = 'INCOMPLETE')
);

CREATE INDEX reconciliation_runs_started_at_idx ON reconciliation_runs (started_at);

-- At most reconciliation.max-stored-mismatches rows per run.
CREATE TABLE reconciliation_mismatches (
    id      BIGSERIAL   PRIMARY KEY,
    run_id  UUID        NOT NULL REFERENCES reconciliation_runs (id),
    kind    VARCHAR(40) NOT NULL CHECK (kind IN ('ENTRY_UNBALANCED', 'BALANCE_DRIFT', 'HOLDS_ACCOUNT_DRIFT',
                'COMPLETED_NOT_CAPTURED', 'CAPTURE_AMOUNT_MISMATCH', 'FAILED_BUT_CAPTURED',
                'OPEN_HOLD_FOR_FINAL_PAYMENT', 'SAGA_STUCK', 'SOURCE_UNAVAILABLE')),
    -- what it is about: a journal entry, account or payment id, a currency, or the unreachable service
    subject VARCHAR(64) NOT NULL,
    details TEXT        NOT NULL
);

CREATE INDEX reconciliation_mismatches_run_id_idx ON reconciliation_mismatches (run_id, id);
