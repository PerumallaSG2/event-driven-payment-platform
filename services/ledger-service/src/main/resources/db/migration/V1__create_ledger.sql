-- Double-entry ledger.
-- Sign convention: a posting's amount is signed, positive = money into the account, so an account's balance is the
-- sum of its postings. accounts.balance caches that sum; it is only changed by LedgerService under a row lock.

CREATE TABLE accounts (
    id             UUID PRIMARY KEY,
    name           VARCHAR(100)   NOT NULL,
    kind           VARCHAR(20)    NOT NULL CHECK (kind IN ('CUSTOMER', 'MERCHANT', 'HOLDS', 'EXTERNAL')),
    currency       VARCHAR(3)     NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    balance        NUMERIC(19, 2) NOT NULL DEFAULT 0,
    allow_negative BOOLEAN        NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ    NOT NULL,
    -- target of the postings FK, so a posting can't be in a different currency than its account
    CONSTRAINT accounts_id_currency_key UNIQUE (id, currency),
    -- backstop for "no overdraft"; LedgerService rejects it first with a clear error
    CONSTRAINT accounts_no_overdraft CHECK (allow_negative OR balance >= 0)
);

-- One HOLDS (suspense) and one EXTERNAL (outside world) account per currency
CREATE UNIQUE INDEX accounts_one_system_account_per_currency
    ON accounts (kind, currency) WHERE kind IN ('HOLDS', 'EXTERNAL');

CREATE TABLE journal_entries (
    id         UUID PRIMARY KEY,
    type       VARCHAR(20) NOT NULL CHECK (type IN ('DEPOSIT', 'HOLD', 'CAPTURE', 'RELEASE')),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE postings (
    id         BIGSERIAL PRIMARY KEY,
    entry_id   UUID           NOT NULL REFERENCES journal_entries (id),
    account_id UUID           NOT NULL,
    currency   VARCHAR(3)     NOT NULL,
    amount     NUMERIC(19, 2) NOT NULL CHECK (amount <> 0),
    CONSTRAINT postings_account_currency_fk FOREIGN KEY (account_id, currency) REFERENCES accounts (id, currency)
);

CREATE INDEX postings_entry_id_idx ON postings (entry_id);
CREATE INDEX postings_account_id_idx ON postings (account_id);

CREATE TABLE holds (
    id                  UUID PRIMARY KEY,
    payer_account_id    UUID           NOT NULL REFERENCES accounts (id),
    merchant_account_id UUID           NOT NULL REFERENCES accounts (id),
    amount              NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    currency            VARCHAR(3)     NOT NULL,
    status              VARCHAR(20)    NOT NULL CHECK (status IN ('OPEN', 'CAPTURED', 'RELEASED')),
    hold_entry_id       UUID           NOT NULL REFERENCES journal_entries (id),
    settle_entry_id     UUID           REFERENCES journal_entries (id),
    created_at          TIMESTAMPTZ    NOT NULL,
    settled_at          TIMESTAMPTZ,
    CONSTRAINT holds_payer_is_not_merchant CHECK (payer_account_id <> merchant_account_id),
    CONSTRAINT holds_settled_iff_not_open
        CHECK ((status = 'OPEN') = (settle_entry_id IS NULL) AND (status = 'OPEN') = (settled_at IS NULL))
);

CREATE INDEX holds_open_idx ON holds (created_at) WHERE status = 'OPEN';

-- ---------------------------------------------------------------------------------------------------------------
-- Deferred constraints, checked at COMMIT: inside a transaction an entry may be half-written, but it can only be
-- committed once its postings sum to zero in every currency and it has at least two of them.
-- ---------------------------------------------------------------------------------------------------------------

CREATE FUNCTION check_entry_sums_to_zero() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    unbalanced RECORD;
BEGIN
    SELECT currency, sum(amount) AS total INTO unbalanced
    FROM postings
    WHERE entry_id = NEW.entry_id
    GROUP BY currency
    HAVING sum(amount) <> 0
    LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'journal entry % does not sum to zero: % % left over', NEW.entry_id, unbalanced.total, unbalanced.currency
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER postings_zero_sum
    AFTER INSERT ON postings
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_entry_sums_to_zero();

CREATE FUNCTION check_entry_has_postings() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (SELECT count(*) FROM postings WHERE entry_id = NEW.id) < 2 THEN
        RAISE EXCEPTION 'journal entry % has fewer than two postings', NEW.id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER journal_entry_has_postings
    AFTER INSERT ON journal_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_entry_has_postings();

-- Append-only: mistakes are corrected with new entries, never by editing history.
CREATE FUNCTION reject_ledger_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only: % is not allowed', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END
$$;

CREATE TRIGGER postings_append_only
    BEFORE UPDATE OR DELETE ON postings
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_change();

CREATE TRIGGER journal_entries_append_only
    BEFORE UPDATE OR DELETE ON journal_entries
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_change();
