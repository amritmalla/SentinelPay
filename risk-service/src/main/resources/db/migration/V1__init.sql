-- V1__init.sql — risk-service (sentinelpay_risk)
-- Purpose: Baseline schema for explainable risk assessments and the risk outbox. New schema, empty DB.
-- Safety:  new schema (no live data, no rewrite).
-- Rollback: drop database / drop schema (greenfield). See docs/architecture/db-migration-plan.md.
-- Source:  docs/architecture/data-architecture.md + ADR-0007, ADR-0008.
-- Note:    velocity counters and the score cache live in Redis, not here (implementations/data/redis).

SET lock_timeout = '5s';
SET statement_timeout = '5min';

CREATE EXTENSION IF NOT EXISTS pgcrypto;   -- gen_random_uuid()

-- ---------------------------------------------------------------------------
-- risk_assessments — one immutable, explainable assessment per transaction.
-- ---------------------------------------------------------------------------
CREATE TABLE risk_assessments (
    id                    uuid         NOT NULL DEFAULT gen_random_uuid(),
    transaction_id        uuid         NOT NULL,          -- the payment being scored
    merchant_id           uuid         NOT NULL,
    score                 numeric(4,3) NOT NULL,
    recommendation        text         NOT NULL,
    contributing_factors  jsonb        NOT NULL DEFAULT '[]'::jsonb,
    features              jsonb,                           -- feature vector used for scoring
    model_version         text         NOT NULL,
    fallback_used         boolean      NOT NULL DEFAULT false,
    latency_ms            integer,
    created_at            timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_risk_assessments PRIMARY KEY (id),
    CONSTRAINT uq_risk_assessments_transaction UNIQUE (transaction_id),
    CONSTRAINT chk_risk_assessments_score CHECK (score >= 0 AND score <= 1),
    CONSTRAINT chk_risk_assessments_recommendation CHECK (recommendation IN ('APPROVE','REVIEW','BLOCK'))
);
-- Lookup by transaction_id (trail / GET /fraud-assessments/{transaction_id}) is served by
-- uq_risk_assessments_transaction. No merchant-trend index in v1 (analytics is a non-goal).

-- PII note: features MAY contain a customer email (test-mode only in v1). Treat as PII; production
-- must minimize/redact per data-architecture.md.
COMMENT ON COLUMN risk_assessments.features IS 'PII: may contain test-mode customer email in the feature vector.';

-- ---------------------------------------------------------------------------
-- risk_outbox — transactional outbox (ADR-0008) for risk.assessed / fraud.alert.high.
-- ---------------------------------------------------------------------------
CREATE TABLE risk_outbox (
    id            bigint       GENERATED ALWAYS AS IDENTITY,
    aggregate     text         NOT NULL,          -- 'risk_assessment'
    aggregate_id  uuid         NOT NULL,
    event_type    text         NOT NULL,          -- 'risk.assessed' | 'fraud.alert.high'
    payload       jsonb        NOT NULL,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    published_at  timestamptz,
    CONSTRAINT pk_risk_outbox PRIMARY KEY (id)
);

-- Index:        idx_risk_outbox_unpublished (partial)
-- Supports:     Relay poll — oldest unpublished events first (FOR UPDATE SKIP LOCKED).
-- Write impact: Low — entries drop out once published; purged after 7 days.
-- Cardinality:  Small working set (unpublished backlog only).
CREATE INDEX idx_risk_outbox_unpublished ON risk_outbox (created_at)
    WHERE published_at IS NULL;
COMMENT ON COLUMN risk_outbox.payload IS 'PII: may contain test-mode customer email in event payloads.';
