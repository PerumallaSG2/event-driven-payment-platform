-- Item 5: fraud-detection's verdict on the payment (from FraudChecked on payment.events), and a person's review of
-- a payment it sent to REVIEW. All null until they happen; a payment whose check failed closed has no verdict.
ALTER TABLE payments
    ADD COLUMN fraud_transaction_id UUID,
    ADD COLUMN fraud_decision       VARCHAR(10) CHECK (fraud_decision IN ('APPROVE', 'REVIEW', 'BLOCK')),
    ADD COLUMN fraud_score          INT,
    -- [{"rule", "points", "reason"}], as fraud-detection returned them
    ADD COLUMN fraud_rules          TEXT CHECK (fraud_rules IS JSON ARRAY),
    ADD COLUMN review_decision      VARCHAR(10) CHECK (review_decision IN ('APPROVE', 'REJECT')),
    ADD COLUMN reviewed_at          TIMESTAMPTZ,
    ADD CONSTRAINT payments_fraud_complete CHECK ((fraud_decision IS NULL) = (fraud_score IS NULL)
        AND (fraud_decision IS NULL) = (fraud_rules IS NULL) AND (fraud_decision IS NULL) = (fraud_transaction_id IS NULL)),
    ADD CONSTRAINT payments_review_complete CHECK ((review_decision IS NULL) = (reviewed_at IS NULL)),
    -- only a payment fraud-detection sent to REVIEW can be reviewed
    ADD CONSTRAINT payments_review_needs_review CHECK (review_decision IS NULL OR fraud_decision = 'REVIEW');

ALTER TABLE payments DROP CONSTRAINT payments_status_known;
ALTER TABLE payments ADD CONSTRAINT payments_status_known
    CHECK (status IN ('CREATED', 'PENDING_REVIEW', 'COMPLETED', 'FAILED'));
