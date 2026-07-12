---
id: 0009
title: Database-per-service (Postgres) + Redis for ephemeral state
status: accepted
date: 2026-07-12
supersedes: null
---

# ADR 0009: Database-per-service (Postgres) + Redis for ephemeral state

## Context

With five services ([ADR-0001](0001-event-driven-microservices-for-v1.md)), data ownership must be defined. The payment aggregate needs transactional integrity and durable idempotency; risk velocity counters and provider health are high-churn, short-lived, and tolerate loss; the design should demonstrate clear ownership boundaries.

## Decision

**PostgreSQL, one database per owning service** (`payments`, `risk`, `provider`, `notifications`). Each service is the sole writer of its tables; cross-service references (e.g., `transactionId`, `merchantId`) are **logical only**, enforced in application code, never via cross-database foreign keys. **Redis** holds only ephemeral, TTL'd data: risk velocity counters + score cache, provider health rolling windows, and gateway rate-limit buckets. Redis is never the source of truth for money state. Schema is managed with **Flyway** migrations per service.

## Consequences

**Benefits:** clear write ownership and service autonomy; strong transactional guarantees where money state lives (idempotency + state machine in one DB); Redis absorbs high-churn counters without polluting the system of record; standardized migrations (an improvement over the reference's partial `schema.sql`).

**Downsides (required):**
- No cross-service referential integrity; consistency across services is the application's responsibility (and can drift).
- Cross-service reads (the decision trail) require composition at read time from multiple stores, not a SQL join.
- Four Postgres instances + Redis is heavier to run locally than a single shared DB.
- Redis loss degrades scoring accuracy and health signals (documented in Failure Modes) — acceptable because those are advisory.

**Revisit when:** operational cost of many Postgres instances outweighs isolation value (a monolith would use one DB with schema separation), or a cross-context read model needs its own store.

## Alternatives considered

- **Single shared Postgres with per-service schemas.** Rejected: weakens the ownership-isolation demonstration central to the microservices showcase; would be the right call in a modular monolith.
- **Redis as source of truth for idempotency/state.** Rejected: money state must be durable and transactional ([ADR-0005](0005-exactly-once-capture-idempotency.md)).
- **A document store (e.g., Mongo) for payments.** Rejected: the payment aggregate is relational and transaction-heavy; Postgres fits the access pattern better.
