-- V1__init.sql — provider-service (sentinelpay_provider)
-- Purpose: Baseline schema for provider transaction references and durable health snapshots.
-- Safety:  new schema (no live data, no rewrite).
-- Rollback: drop database / drop schema (greenfield). See docs/architecture/db-migration-plan.md.
-- Source:  docs/architecture/data-architecture.md + ADR-0006.
-- Note:    the rolling provider-health window lives in Redis; this DB holds the durable snapshot only.

SET lock_timeout = '5s';
SET statement_timeout = '5min';

CREATE EXTENSION IF NOT EXISTS pgcrypto;   -- gen_random_uuid()

-- ---------------------------------------------------------------------------
-- provider_txn_refs — reference to an external provider authorize/capture/refund call.
-- payment_id is a logical reference into sentinelpay_payments (no cross-database FK, ADR-0009).
-- ---------------------------------------------------------------------------
CREATE TABLE provider_txn_refs (
    id            uuid         NOT NULL DEFAULT gen_random_uuid(),
    payment_id    uuid         NOT NULL,          -- logical ref; enforced in application code
    provider      text         NOT NULL,
    provider_ref  text         NOT NULL,
    kind          text         NOT NULL,
    amount_cents  bigint,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_provider_txn_refs PRIMARY KEY (id),
    CONSTRAINT uq_provider_txn_refs_provider_ref UNIQUE (provider, provider_ref),
    CONSTRAINT chk_provider_txn_refs_provider CHECK (provider IN ('stripe','mockpay')),
    CONSTRAINT chk_provider_txn_refs_kind CHECK (kind IN ('AUTHORIZE','CAPTURE','REFUND'))
);
-- Lookup by (provider, provider_ref) — reconciling an ambiguous authorization — is served by
-- uq_provider_txn_refs_provider_ref.

-- Index:        idx_provider_txn_refs_payment_id
-- Supports:     Load all provider calls for a payment (reconciliation by payment_id).
-- Write impact: Low — one to a few rows per payment.
-- Cardinality:  payment_id high.
CREATE INDEX idx_provider_txn_refs_payment_id ON provider_txn_refs (payment_id);

-- ---------------------------------------------------------------------------
-- provider_health_snapshots — one durable row per provider (rolling window is in Redis).
-- ---------------------------------------------------------------------------
CREATE TABLE provider_health_snapshots (
    provider       text         NOT NULL,
    state          text         NOT NULL,
    success_rate   numeric(5,4),
    p95_latency_ms integer,
    updated_at     timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_provider_health_snapshots PRIMARY KEY (provider),
    CONSTRAINT chk_provider_health_provider CHECK (provider IN ('stripe','mockpay')),
    CONSTRAINT chk_provider_health_state CHECK (state IN ('HEALTHY','DEGRADED','UNAVAILABLE')),
    CONSTRAINT chk_provider_health_success_rate CHECK (
        success_rate IS NULL OR (success_rate >= 0 AND success_rate <= 1))
);
-- No secondary index: tiny table (one row per provider), always accessed by PK.
