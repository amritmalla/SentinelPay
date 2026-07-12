-- V2__attempt_intent.sql — durable pre-call intent on payment_attempts (Phase 2).
SET lock_timeout = '5s';

ALTER TABLE payment_attempts ADD COLUMN downstream_key text;

ALTER TABLE payment_attempts DROP CONSTRAINT chk_payment_attempts_outcome;
ALTER TABLE payment_attempts ADD CONSTRAINT chk_payment_attempts_outcome CHECK (outcome IN (
    'STARTED','AUTHORIZED','CAPTURED','HARD_FAIL','RETRYABLE','AMBIGUOUS_TIMEOUT','RECONCILED'));

CREATE INDEX idx_payment_attempts_downstream_key ON payment_attempts (payment_id, downstream_key)
    WHERE downstream_key IS NOT NULL;
