-- V2__provider_outbox.sql — transactional outbox for provider.health.changed events (ADR-0008).

CREATE TABLE provider_outbox (
    id            bigint       GENERATED ALWAYS AS IDENTITY,
    aggregate     text         NOT NULL,
    aggregate_id  uuid         NOT NULL,
    event_type    text         NOT NULL,
    payload       jsonb        NOT NULL,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    published_at  timestamptz,
    CONSTRAINT pk_provider_outbox PRIMARY KEY (id)
);

CREATE INDEX idx_provider_outbox_unpublished ON provider_outbox (created_at)
    WHERE published_at IS NULL;

COMMENT ON COLUMN provider_outbox.payload IS 'Provider health change event payload.';
