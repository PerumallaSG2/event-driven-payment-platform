-- The saga's hold for a payment. One hold per payment, so a re-sent HoldFunds finds the existing hold instead of
-- reserving twice. NULL for holds created through the REST API (setup and tests), which have no payment.
ALTER TABLE holds ADD COLUMN payment_id UUID UNIQUE;

-- A ReleaseHold that arrived before any hold existed (the saga gave up waiting). A HoldFunds for a voided payment is
-- rejected, so a late hold can't strand the payer's money.
CREATE TABLE voided_payments (
    payment_id UUID        PRIMARY KEY,
    voided_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Commands already handled, keyed by the event-id header: a redelivered command is skipped.
CREATE TABLE inbox (
    event_id    UUID         PRIMARY KEY,
    topic       VARCHAR(100) NOT NULL,
    received_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Replies on ledger.events, written in the same transaction as the postings and published by OutboxRelay.
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
