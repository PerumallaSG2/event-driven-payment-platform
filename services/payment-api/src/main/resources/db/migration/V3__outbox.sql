-- Transactional outbox: business code inserts the event here in the same transaction as its own writes, and
-- OutboxRelay publishes it to Kafka afterwards. A crash after the send but before marking the row published means
-- the event is sent again; consumers de-duplicate on event_id (the event-id header).
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
