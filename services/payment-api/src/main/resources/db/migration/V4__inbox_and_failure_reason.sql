-- payment.events already handled, keyed by the event-id header: a redelivered event is skipped.
CREATE TABLE inbox (
    event_id    UUID         PRIMARY KEY,
    topic       VARCHAR(100) NOT NULL,
    received_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Why a payment FAILED (the ledger's rejection reason, or HOLD_TIMEOUT); null otherwise.
ALTER TABLE payments ADD COLUMN failure_reason VARCHAR(50);
ALTER TABLE payments ADD CONSTRAINT payments_status_known CHECK (status IN ('CREATED', 'COMPLETED', 'FAILED'));
ALTER TABLE payments ADD CONSTRAINT payments_failed_has_reason CHECK ((status = 'FAILED') = (failure_reason IS NOT NULL));
