-- Everything the saga needs: the ledger accounts to move money between, and the card/merchant fields that item 5
-- passes unchanged to fraud-detection. All NOT NULL without defaults: a dev volume with older payments needs
-- `docker compose -f infra/docker-compose.yml down -v`.
ALTER TABLE payments
    ADD COLUMN payer_account_id    UUID             NOT NULL,
    ADD COLUMN merchant_account_id UUID             NOT NULL,
    ADD COLUMN card_id             VARCHAR(64)      NOT NULL,
    ADD COLUMN merchant            VARCHAR(100)     NOT NULL,
    ADD COLUMN merchant_category   VARCHAR(50)      NOT NULL,
    ADD COLUMN city                VARCHAR(100)     NOT NULL,
    ADD COLUMN latitude            DOUBLE PRECISION NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    ADD COLUMN longitude           DOUBLE PRECISION NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    -- timestamptz keeps only the instant, so the original UTC offset is stored beside it (as in fraud-detection's
    -- V2): fraud-detection must receive the timestamp unchanged, and its odd-hours rule reads the local time.
    ADD COLUMN transaction_time           TIMESTAMPTZ NOT NULL,
    ADD COLUMN transaction_offset_seconds INT         NOT NULL CHECK (transaction_offset_seconds BETWEEN -64800 AND 64800),
    ADD CONSTRAINT payments_payer_not_merchant CHECK (payer_account_id <> merchant_account_id),
    ADD CONSTRAINT payments_currency_iso CHECK (currency ~ '^[A-Z]{3}$');

-- One row per client Idempotency-Key. IN_PROGRESS: claimed by a request that is still working (or crashed; the
-- claim is stale once locked_until passes). COMPLETED: the payment and the response to replay are stored.
CREATE TABLE idempotency_keys (
    idempotency_key VARCHAR(255) PRIMARY KEY,
    request_hash    CHAR(64)     NOT NULL,
    status          VARCHAR(20)  NOT NULL CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    claim_token     UUID         NOT NULL,
    locked_until    TIMESTAMPTZ  NOT NULL,
    payment_id      UUID         REFERENCES payments (id),
    response_status INT,
    response_body   TEXT,
    created_at      TIMESTAMPTZ  NOT NULL,
    expires_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT idempotency_keys_completed_has_response CHECK (
        (status = 'COMPLETED') = (payment_id IS NOT NULL AND response_status IS NOT NULL AND response_body IS NOT NULL))
);

CREATE INDEX idempotency_keys_expires_at_idx ON idempotency_keys (expires_at);
