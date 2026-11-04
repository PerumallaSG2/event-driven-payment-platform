-- One row per payment: where its saga stands. Each transition (inbox row + saga update + the next command or the
-- outcome as an outbox row) commits in one transaction.
--   HOLD_REQUESTED -> CAPTURE_REQUESTED -> COMPLETED
--   HOLD_REQUESTED -> FAILED                              (the ledger rejected the hold; nothing to undo)
--   HOLD_REQUESTED -> RELEASE_REQUESTED -> FAILED         (compensation: no answer before deadline_at)
CREATE TABLE sagas (
    payment_id          UUID           PRIMARY KEY,
    payer_account_id    UUID           NOT NULL,
    merchant_account_id UUID           NOT NULL,
    amount              NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    currency            VARCHAR(3)     NOT NULL,
    state               VARCHAR(20)    NOT NULL CHECK (state IN
                            ('HOLD_REQUESTED', 'CAPTURE_REQUESTED', 'RELEASE_REQUESTED', 'COMPLETED', 'FAILED')),
    failure_reason      VARCHAR(50),
    attempts            INT            NOT NULL CHECK (attempts >= 1),   -- sends of the current command
    created_at          TIMESTAMPTZ    NOT NULL,
    updated_at          TIMESTAMPTZ    NOT NULL,
    deadline_at         TIMESTAMPTZ    NOT NULL,                         -- HOLD_REQUESTED after this: compensate
    version             BIGINT         NOT NULL,
    CONSTRAINT sagas_failed_has_reason CHECK (state <> 'FAILED' OR failure_reason IS NOT NULL)
);

-- The stuck-saga sweep only looks at sagas waiting for the ledger.
CREATE INDEX sagas_waiting_idx ON sagas (updated_at)
    WHERE state IN ('HOLD_REQUESTED', 'CAPTURE_REQUESTED', 'RELEASE_REQUESTED');

CREATE TABLE inbox (
    event_id    UUID         PRIMARY KEY,
    topic       VARCHAR(100) NOT NULL,
    received_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE outbox (
    id           BIGSERIAL    PRIMARY KEY,
    event_id     UUID         NOT NULL UNIQUE,
    topic        VARCHAR(100) NOT NULL,
    msg_key      VARCHAR(100) NOT NULL,
    event_type   VARCHAR(50)  NOT NULL,
    payload      TEXT         NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);

CREATE INDEX outbox_unpublished_idx ON outbox (id) WHERE published_at IS NULL;
CREATE INDEX outbox_published_at_idx ON outbox (published_at) WHERE published_at IS NOT NULL;
