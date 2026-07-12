# DB Migration Plan — V1 baseline (all services)

## Summary

Greenfield `V1__init.sql` for each of the four service databases, derived from
[data-architecture.md](data-architecture.md). Each creates the service's baseline tables,
constraints, and indexes against an **empty** database. There is no live data, no rewrite, no
backfill, and no application cutover — so the expand/migrate/contract phasing does **not** apply.

| Database | Migration | Tables |
|---|---|---|
| `sentinelpay_payments` | `payment-service/.../db/migration/V1__init.sql` | payments, payment_attempts, refunds, payment_status_history, idempotency_keys, payment_outbox |
| `sentinelpay_risk` | `risk-service/.../db/migration/V1__init.sql` | risk_assessments, risk_outbox |
| `sentinelpay_provider` | `provider-service/.../db/migration/V1__init.sql` | provider_txn_refs, provider_health_snapshots |
| `sentinelpay_notifications` | `notification-service/.../db/migration/V1__init.sql` | notifications, delivery_attempts |

## Scope & key integrity rules enforced in-DB

- **Exactly-once capture (ADR-0005):** `idempotency_keys` PK `(merchant_id, idempotency_key)` +
  `request_hash` (conflict detection) + `response_body` (replay); `payments.version` optimistic-lock
  column (ADR-0010).
- **Provider-agnostic (ADR-0006):** provider values constrained to `('stripe','mockpay')` via CHECK.
- **Explainability:** `risk_assessments` immutable, `unique(transaction_id)`, score bounded `[0,1]`.
- **Outbox (ADR-0008):** per-service `*_outbox` with a partial index on unpublished rows.
- **Ownership (ADR-0009):** cross-service references (`payment_id` in provider/risk) are **logical
  only** — no cross-database foreign keys.
- All timestamps `timestamptz` (UTC); all status/enum columns CHECK-constrained; every FK is indexed
  or covered by a composite unique whose leading column is the FK.

## Phases

- **Expand / Migrate / Contract: N/A.** These are `V1` creations against empty databases (no trigger
  from the migration-safety rules applies — no column drop, type change, NOT NULL tightening on live
  data, >10k-row backfill, index on a live write-heavy table, or rename).

## Dry Run

**Status: PERFORMED — clean (2026-07-12).**

- **Flyway apply:** the full `./mvnw verify` ran every service's Testcontainers integration test against
  a real PostgreSQL 15; Flyway reported **"Successfully applied 1 migration"** for all four databases
  and every service context booted. All tests: `Tests run: 1, Failures: 0, Errors: 0`.
- **`EXPLAIN` (payments, 20k seeded rows across 50 merchants, PostgreSQL 16):** the planner chooses the
  intended index for each justified query pattern:

  | Query pattern | Chosen plan |
  |---|---|
  | `WHERE merchant_id = ? ORDER BY created_at DESC LIMIT 50` | `Index Scan using idx_payments_merchant_created` |
  | `WHERE merchant_id = ? AND idempotency_key = ?` | `Index Scan using pk_idempotency_keys` |
  | `WHERE published_at IS NULL ORDER BY created_at LIMIT 100` | `Index Scan using idx_payment_outbox_unpublished` (partial) |
  | `WHERE status IN ('AUTHORIZING','IN_REVIEW')` | `Index Scan using idx_payments_inflight` (partial) |

Reproduce with `./mvnw verify` (Docker required) or apply a single `V1__init.sql` against
`docker run --rm -e POSTGRES_PASSWORD=x -d postgres:16` and run the queries above.

## Monitoring (once live)

Outbox depth and relay lag; `payments` lock waits / `SELECT FOR UPDATE` contention; deadlocks;
connection-pool saturation per service; Flyway schema-history integrity. (Detail in
[data-architecture.md](data-architecture.md) → Operational Readiness.)

## Deferred Risks

- **PII in test mode:** `payment_outbox.payload`, `risk_assessments.features`, and
  `notifications.recipient` may carry a customer email. v1 uses Stripe **test** tokens/emails only;
  production PII minimization/redaction and retention are owned by data-architecture.md and deferred.
