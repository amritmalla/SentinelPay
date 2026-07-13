-- V3__processed_webhook_events.sql — dedup table for Stripe webhook reconciliation.
CREATE TABLE processed_webhook_events (
    event_id     text        NOT NULL,
    event_type   text        NOT NULL,
    received_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_processed_webhook_events PRIMARY KEY (event_id)
);
