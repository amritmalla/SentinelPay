-- V1__init.sql — payment-service (sentinelpay_payments)
-- Purpose: Baseline schema for the payment aggregate — lifecycle, attempts, refunds, idempotency,
--          status history, and the transactional outbox. New schema against an empty database.
-- Safety:  new schema (no live data, no rewrite).
-- Rollback: drop database / drop schema (greenfield). See docs/architecture/db-migration-plan.md.
-- Source:  docs/architecture/data-architecture.md + backend-architecture.md (ADR-0005, 0008, 0010).

SET lock_timeout = '5s';
SET statement_timeout = '5min';

-- Extensions
CREATE EXTENSION IF NOT EXISTS pgcrypto;   -- gen_random_uuid()

-- ---------------------------------------------------------------------------
-- payments — aggregate root and system of record for the payment lifecycle.
-- ---------------------------------------------------------------------------
CREATE TABLE payments (
    id                    uuid         NOT NULL DEFAULT gen_random_uuid(),
    merchant_id           uuid         NOT NULL,
    status                text         NOT NULL,
    amount_cents          bigint       NOT NULL,
    currency              char(3)      NOT NULL,
    provider              text,                          -- null until a provider is selected
    risk_score            numeric(4,3),                  -- snapshot of the assessment the decision acted on
    risk_recommendation   text,
    risk_model_version    text,
    risk_fallback_used    boolean,
    failure_reason        text,
    version               bigint       NOT NULL DEFAULT 0,   -- optimistic lock (ADR-0010)
    created_at            timestamptz  NOT NULL DEFAULT now(),
    updated_at            timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_payments PRIMARY KEY (id),
    CONSTRAINT chk_payments_status CHECK (status IN (
        'CREATED','RISK_EVALUATED','BLOCKED','IN_REVIEW','AUTHORIZING',
        'AUTHORIZED','CAPTURED','COMPLETED','FAILED','REFUND_PENDING','REFUNDED')),
    CONSTRAINT chk_payments_amount_positive CHECK (amount_cents > 0),
    CONSTRAINT chk_payments_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_payments_provider CHECK (provider IS NULL OR provider IN ('stripe','mockpay')),
    CONSTRAINT chk_payments_risk_recommendation CHECK (
        risk_recommendation IS NULL OR risk_recommendation IN ('APPROVE','REVIEW','BLOCK'))
);

-- Index:        idx_payments_merchant_created
-- Supports:     GET /api/v1/payments — list a merchant's payments, newest first (cursor pagination).
-- Write impact: Low — one insert per charge; (merchant_id, created_at) are immutable.
-- Cardinality:  merchant_id high, created_at high.
CREATE INDEX idx_payments_merchant_created ON payments (merchant_id, created_at DESC);

-- Index:        idx_payments_inflight (partial)
-- Supports:     Ambiguous-timeout reconciliation sweep + operator review of held payments.
-- Write impact: Negligible — index only covers transient rows; entries leave as status settles.
-- Cardinality:  Tiny working set (few in-flight/held payments at any time).
CREATE INDEX idx_payments_inflight ON payments (status)
    WHERE status IN ('AUTHORIZING','IN_REVIEW');

-- ---------------------------------------------------------------------------
-- payment_attempts — one row per provider authorize/capture attempt (append-only).
-- ---------------------------------------------------------------------------
CREATE TABLE payment_attempts (
    id             uuid         NOT NULL DEFAULT gen_random_uuid(),
    payment_id     uuid         NOT NULL,
    attempt_number smallint     NOT NULL,
    provider       text         NOT NULL,
    outcome        text         NOT NULL,
    provider_ref   text,
    latency_ms     integer,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_payment_attempts PRIMARY KEY (id),
    CONSTRAINT fk_payment_attempts_payment FOREIGN KEY (payment_id) REFERENCES payments (id),
    CONSTRAINT uq_payment_attempts_payment_number UNIQUE (payment_id, attempt_number),
    CONSTRAINT chk_payment_attempts_provider CHECK (provider IN ('stripe','mockpay')),
    CONSTRAINT chk_payment_attempts_outcome CHECK (outcome IN (
        'AUTHORIZED','CAPTURED','HARD_FAIL','RETRYABLE','AMBIGUOUS_TIMEOUT','RECONCILED')),
    CONSTRAINT chk_payment_attempts_number CHECK (attempt_number > 0)
);
-- FK (payment_id) is served by uq_payment_attempts_payment_number (leading column payment_id);
-- no separate index needed — also serves "list attempts for a payment" (trail).

-- ---------------------------------------------------------------------------
-- refunds — refund requests against a completed payment.
-- ---------------------------------------------------------------------------
CREATE TABLE refunds (
    id            uuid         NOT NULL DEFAULT gen_random_uuid(),
    payment_id    uuid         NOT NULL,
    amount_cents  bigint       NOT NULL,
    status        text         NOT NULL,
    reason        text,
    provider_ref  text,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_refunds PRIMARY KEY (id),
    CONSTRAINT fk_refunds_payment FOREIGN KEY (payment_id) REFERENCES payments (id),
    CONSTRAINT chk_refunds_amount_positive CHECK (amount_cents > 0),
    CONSTRAINT chk_refunds_status CHECK (status IN ('REFUND_PENDING','REFUNDED','FAILED'))
);

-- Index:        idx_refunds_payment_id
-- Supports:     Load refunds for a payment (FK lookup; refund history on the payment resource).
-- Write impact: Low — refunds are infrequent.
-- Cardinality:  payment_id high.
CREATE INDEX idx_refunds_payment_id ON refunds (payment_id);

-- ---------------------------------------------------------------------------
-- payment_status_history — immutable record of lifecycle transitions.
-- ---------------------------------------------------------------------------
CREATE TABLE payment_status_history (
    id           bigint       GENERATED ALWAYS AS IDENTITY,
    payment_id   uuid         NOT NULL,
    from_status  text,
    to_status    text         NOT NULL,
    occurred_at  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_payment_status_history PRIMARY KEY (id),
    CONSTRAINT fk_payment_status_history_payment FOREIGN KEY (payment_id) REFERENCES payments (id)
);

-- Index:        idx_payment_status_history_payment
-- Supports:     Reconstruct the status timeline for a payment (trail).
-- Write impact: Low — append-only, a few rows per payment.
-- Cardinality:  payment_id high.
CREATE INDEX idx_payment_status_history_payment ON payment_status_history (payment_id, occurred_at);

-- ---------------------------------------------------------------------------
-- idempotency_keys — charge/refund idempotency guard (ADR-0005). Scoped per merchant.
-- Stores request_hash to detect IDEMPOTENCY_CONFLICT and response_body to replay the original result.
-- ---------------------------------------------------------------------------
CREATE TABLE idempotency_keys (
    merchant_id      uuid         NOT NULL,
    idempotency_key  text         NOT NULL,
    request_hash     text         NOT NULL,
    response_status  smallint     NOT NULL,
    response_body    jsonb        NOT NULL,
    payment_id       uuid,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    expires_at       timestamptz  NOT NULL,          -- created_at + 72h (retention window)
    CONSTRAINT pk_idempotency_keys PRIMARY KEY (merchant_id, idempotency_key),
    CONSTRAINT fk_idempotency_keys_payment FOREIGN KEY (payment_id) REFERENCES payments (id)
);
-- FK (payment_id) is nullable and low-cardinality-per-row; a dedicated index is unnecessary because
-- lookups are by the (merchant_id, idempotency_key) PK, never by payment_id, in this direction.

-- Index:        idx_idempotency_keys_expires_at
-- Supports:     Scheduled purge of expired keys (retention 72h, ADR-0011 sibling policy).
-- Write impact: Low — one insert per charge; expires_at immutable.
-- Cardinality:  expires_at high.
CREATE INDEX idx_idempotency_keys_expires_at ON idempotency_keys (expires_at);

-- ---------------------------------------------------------------------------
-- payment_outbox — transactional outbox (ADR-0008); drained by a relay to Kafka.
-- ---------------------------------------------------------------------------
CREATE TABLE payment_outbox (
    id            bigint       GENERATED ALWAYS AS IDENTITY,
    aggregate     text         NOT NULL,          -- e.g. 'payment'
    aggregate_id  uuid         NOT NULL,
    event_type    text         NOT NULL,          -- e.g. 'payment.completed'
    payload       jsonb        NOT NULL,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    published_at  timestamptz,
    CONSTRAINT pk_payment_outbox PRIMARY KEY (id)
);

-- Index:        idx_payment_outbox_unpublished (partial)
-- Supports:     Relay poll — oldest unpublished events first (FOR UPDATE SKIP LOCKED).
-- Write impact: Low — entries drop out of the index once published_at is set; purged after 7 days.
-- Cardinality:  Small working set (unpublished backlog only).
CREATE INDEX idx_payment_outbox_unpublished ON payment_outbox (created_at)
    WHERE published_at IS NULL;

-- PII note: payment_outbox.payload MAY contain a customer email (test-mode only in v1). Treat as PII;
-- production must apply the payload retention/redaction policy from data-architecture.md.
COMMENT ON COLUMN payment_outbox.payload IS 'PII: may contain test-mode customer email in event payloads.';
