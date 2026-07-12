-- V1__init.sql — notification-service (sentinelpay_notifications)
-- Purpose: Baseline schema for notifications and their delivery attempts. New schema, empty DB.
-- Safety:  new schema (no live data, no rewrite).
-- Rollback: drop database / drop schema (greenfield). See docs/architecture/db-migration-plan.md.
-- Source:  docs/architecture/data-architecture.md + backend-architecture.md.

SET lock_timeout = '5s';
SET statement_timeout = '5min';

CREATE EXTENSION IF NOT EXISTS pgcrypto;   -- gen_random_uuid()

-- ---------------------------------------------------------------------------
-- notifications — one row per consumed business event, idempotent by event_id.
-- ---------------------------------------------------------------------------
CREATE TABLE notifications (
    id          uuid         NOT NULL DEFAULT gen_random_uuid(),
    event_id    uuid         NOT NULL,
    event_type  text         NOT NULL,
    recipient   text         NOT NULL,          -- PII: test-mode email in v1
    channel     text         NOT NULL,
    status      text         NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_notifications PRIMARY KEY (id),
    CONSTRAINT uq_notifications_event_id UNIQUE (event_id),
    CONSTRAINT chk_notifications_channel CHECK (channel IN ('EMAIL')),
    CONSTRAINT chk_notifications_status CHECK (status IN ('PENDING','SENT','DELIVERED','FAILED'))
);
-- Dedup lookup by event_id (consumer idempotency) is served by uq_notifications_event_id.
COMMENT ON COLUMN notifications.recipient IS 'PII: notification recipient (test-mode email in v1).';

-- Index:        idx_notifications_failed (partial)
-- Supports:     Retry scan of failed deliveries.
-- Write impact: Negligible — only failed rows are indexed; entries leave on success.
-- Cardinality:  Small working set (failed backlog only).
CREATE INDEX idx_notifications_failed ON notifications (status)
    WHERE status = 'FAILED';

-- ---------------------------------------------------------------------------
-- delivery_attempts — one row per delivery attempt for a notification (append-only).
-- ---------------------------------------------------------------------------
CREATE TABLE delivery_attempts (
    id               uuid         NOT NULL DEFAULT gen_random_uuid(),
    notification_id  uuid         NOT NULL,
    channel          text         NOT NULL,
    status           text         NOT NULL,
    detail           text,
    attempted_at     timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_delivery_attempts PRIMARY KEY (id),
    CONSTRAINT fk_delivery_attempts_notification FOREIGN KEY (notification_id) REFERENCES notifications (id),
    CONSTRAINT chk_delivery_attempts_status CHECK (status IN ('SENT','FAILED'))
);

-- Index:        idx_delivery_attempts_notification_id
-- Supports:     Load delivery history for a notification (FK lookup).
-- Write impact: Low — a few rows per notification.
-- Cardinality:  notification_id high.
CREATE INDEX idx_delivery_attempts_notification_id ON delivery_attempts (notification_id);
