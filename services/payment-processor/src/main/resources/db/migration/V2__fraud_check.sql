-- Item 5: a fraud check between the hold and the capture.
--   HOLD_REQUESTED -> FRAUD_CHECK -> CAPTURE_REQUESTED -> COMPLETED                       (APPROVE)
--   HOLD_REQUESTED -> FRAUD_CHECK -> RELEASE_REQUESTED -> FAILED                          (BLOCK, or no answer: fail closed)
--   HOLD_REQUESTED -> FRAUD_CHECK -> PENDING_REVIEW -> CAPTURE_REQUESTED | RELEASE_REQUESTED  (REVIEW, then a person)

-- The card/merchant fields go to fraud-detection unchanged. timestamptz keeps only the instant, so the original UTC
-- offset is stored beside it (as in payment-api). Nullable only for sagas started before item 5; every
-- payment.initiated since carries them.
ALTER TABLE sagas
    ADD COLUMN card_id                    VARCHAR(64),
    ADD COLUMN merchant                   VARCHAR(100),
    ADD COLUMN merchant_category          VARCHAR(50),
    ADD COLUMN city                       VARCHAR(100),
    ADD COLUMN latitude                   DOUBLE PRECISION,
    ADD COLUMN longitude                  DOUBLE PRECISION,
    ADD COLUMN transaction_time           TIMESTAMPTZ,
    ADD COLUMN transaction_offset_seconds INT CHECK (transaction_offset_seconds BETWEEN -64800 AND 64800),
    -- fraud-detection's id for this payment's transaction, once it has scored it
    ADD COLUMN fraud_transaction_id       UUID;

ALTER TABLE sagas DROP CONSTRAINT sagas_state_check;
ALTER TABLE sagas ADD CONSTRAINT sagas_state_check CHECK (state IN ('HOLD_REQUESTED', 'FRAUD_CHECK', 'PENDING_REVIEW',
    'CAPTURE_REQUESTED', 'RELEASE_REQUESTED', 'COMPLETED', 'FAILED'));

-- deadline_at now also ends a review: PENDING_REVIEW past it is compensated (REVIEW_TIMEOUT).
-- The sweep also looks at FRAUD_CHECK (stale: the app stopped mid-check) and PENDING_REVIEW.
DROP INDEX sagas_waiting_idx;
CREATE INDEX sagas_waiting_idx ON sagas (updated_at)
    WHERE state IN ('HOLD_REQUESTED', 'FRAUD_CHECK', 'PENDING_REVIEW', 'CAPTURE_REQUESTED', 'RELEASE_REQUESTED');
