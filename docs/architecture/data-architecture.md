---
product: sentinelpay
status: approved
owner: self
system_design: system-design.md
prd: ../product/PRD.md
version: 0.1.0
last_reviewed: 2026-07-12
---

# Data Architecture: SentinelPay (v1)

## Overview

SentinelPay v1 uses **database-per-service PostgreSQL** for all durable state, **Redis** for ephemeral coordination (velocity counters, score cache, provider-health windows, rate-limit buckets), and **Kafka** as a bounded-retention event log for after-the-fact fan-out. The dominant workload is the synchronous charge path: a point-lookup idempotency check, a gRPC risk assessment, one-to-two provider attempts, and a single transactional write grouping (`payment` + `payment_attempt` + `idempotency_record` + `payment_outbox`) — this grouping is what guarantees the PRD's **double-charge = 0**. The architecture optimizes for **transactional integrity on money state, exactly-once execution across failover, and an explainable trail**. It intentionally does **not** optimize for analytics, search, high-scale sharding, multi-region, or multi-tenant isolation (all PRD non-goals); Redis is never a system of record for money.

Engine and DB-per-service decisions are fixed by [ADR-0009](adrs/0009-database-per-service.md); this document details ownership, access patterns, the consistency model, indexing, retention, and operational posture, and adds [ADR-0010](adrs/0010-payment-optimistic-concurrency.md) (payment concurrency) and [ADR-0011](adrs/0011-bounded-event-and-outbox-retention.md) (bounded event/outbox retention).

## Dataset Inventory & Ownership

| Dataset | Owning Context | Authoritative Write Path | Engine | Consumers (read) | Consumption Mechanism |
|---|---|---|---|---|---|
| `payment` | Payment Execution | Payment Service charge/refund/webhook handlers | PostgreSQL (`payments`) | Payment (self), Decision Trail | API |
| `payment_attempt` | Payment Execution | Payment Service failover loop | PostgreSQL (`payments`) | Decision Trail | API |
| `refund` | Payment Execution | Payment Service refund handler | PostgreSQL (`payments`) | Payment (self) | API |
| `idempotency_record` | Payment Execution | Payment Service charge handler (insert-first) | PostgreSQL (`payments`) | Payment (self) | internal |
| `payment_outbox` | Payment Execution | Payment Service (same txn as state change) | PostgreSQL (`payments`) | Payment relay → Kafka | outbox relay |
| `risk_assessment` | Risk Intelligence | Risk Service scoring orchestrator | PostgreSQL (`risk`) | Decision Trail | API |
| `risk_velocity_counter` | Risk Intelligence | Risk Service (post-score increment) | Redis | Risk (self) | in-service |
| `risk_score_cache` | Risk Intelligence | Risk Service (post-score write) | Redis | Risk (self) | in-service |
| `risk_outbox` | Risk Intelligence | Risk Service (same txn as assessment) | PostgreSQL (`risk`) | Risk relay → Kafka | outbox relay |
| `provider_txn_ref` | Provider Integration | Provider Service authorize/capture | PostgreSQL (`provider`) | Payment (reconciliation) | API |
| `provider_health_window` | Provider Integration | Provider Service (per-attempt outcome) | Redis (+ periodic PG snapshot) | Payment routing, Provider | API / in-service |
| `notification` + `delivery_attempt` | Notification Delivery | Notification Service consumer | PostgreSQL (`notifications`) | Notification (self) | internal |
| `ratelimit_bucket` | Edge & Access | API Gateway limiter | Redis | Gateway (self) | in-service |
| `event_log` (Kafka topics) | Producing service per topic | Producer via outbox relay | Kafka | Notification, future consumers | event subscription |

No dataset has more than one owning context or write path. Cross-context reads (the Decision Trail composing `payment_attempt` + `risk_assessment`) go through each owning service's API — **no cross-database joins** ([ADR-0009](adrs/0009-database-per-service.md)).

## Access Patterns

| Dataset | Read Shapes | Write Shapes | Hot Keys / Skew | Read:Write | Latency Target | Transactional Grouping |
|---|---|---|---|---|---|---|
| `payment` | point (by id; by merchant_id+idempotency_key), range (list by merchant_id, created_at) | single-row insert + validated status transitions | active merchant during demo/chaos runs | ~2:1 | on 150 ms critical path | **grouped** with `payment_attempt`, `idempotency_record`, `payment_outbox` |
| `payment_attempt` | range by payment_id (trail) | append (1–2 rows/charge; more under failover) | none | 1:1 | on critical path | in payment txn |
| `idempotency_record` | point by (merchant_id, key) | insert-first (unique); read on retry | retried key bursts | 1:1 | on critical path (first check) | in payment txn |
| `refund` | point by id; by payment_id | insert + status transition | none | low volume | async-tolerant | in payment txn |
| `payment_outbox` / `risk_outbox` | range scan of unpublished (created_at asc) | append; mark-published | polling worker | write-heavy | relay <1 s | in owning write txn |
| `risk_assessment` | point by transaction_id (trail) | append-only (1/charge) | none | 1:1 | on critical path | in risk txn (with `risk_outbox`) |
| `risk_velocity_counter` | GET per window | INCR + EXPIRE | per-customer-email; skew if one buyer floods | 1:1 | sub-ms | none (atomic op) |
| `risk_score_cache` | GET by hash key | SET w/ TTL | repeated identical charges | read-biased | sub-ms | none |
| `provider_txn_ref` | point by payment_id / provider ref (reconciliation) | append per provider call | none | write-biased | on critical path | none |
| `provider_health_window` | GET per provider (routing) | rolling INCR per outcome | 2 providers → low cardinality, naturally hot | read-biased | sub-ms | none |
| `notification` + `delivery_attempt` | point by event_id (dedup); by status (retry) | insert + status update | none | 1:1 | async | in notification txn |
| `ratelimit_bucket` | token check | decrement/refill | per-user/IP | 1:1 | sub-ms | none |
| `event_log` | sequential consume by offset | append (keyed by merchantId/transactionId) | active merchant partition | consume-biased | async | none |

## Engine Selection

| Dataset | Engine Class | Engine | Justification | Alternatives Rejected |
|---|---|---|---|---|
| `payment`, `payment_attempt`, `refund`, `idempotency_record`, `*_outbox` | relational | PostgreSQL | Multi-row transactional grouping + unique constraints + optimistic versioning are exactly what exactly-once capture needs; relational shape (payment→attempts→refunds) | **MongoDB** — no multi-document txn need met more simply, and money state wants strict integrity; **Redis** — non-durable, disqualified for money state |
| `risk_assessment` | relational | PostgreSQL | Append-only structured record with per-field explainability + transactional pairing with `risk_outbox` | **Document store** — assessment shape is stable/structured; **Elasticsearch** — not a system of record (v1 has no search need) |
| `risk_velocity_counter`, `risk_score_cache`, `provider_health_window`, `ratelimit_bucket` | key-value | Redis | Sub-ms atomic counters + native TTL; loss is tolerable degradation, never corruption | **Postgres** — row-write churn + TTL sweeps are the wrong tool for hot ephemeral counters; **in-process cache** — not shared across horizontally-scaled instances |
| `event_log` | log / streaming | Kafka | Durable, ordered, partitioned append log for decoupled fan-out; keys give per-aggregate ordering | **DB-table queue** — loses partitioned ordering + consumer-group scaling; **RabbitMQ** — reuse of existing stack + replay favor Kafka |

## Consistency & Concurrency Model

| Write Path | Consistency Guarantee | Isolation / Concurrency | Conflict Resolution | Enforcement Mechanism |
|---|---|---|---|---|
| Charge (payment + attempt + idempotency + outbox) | strong, read-your-writes | READ COMMITTED + `SELECT … FOR UPDATE` on the payment row during transitions | first writer wins; duplicate idempotency key returns stored outcome | single Postgres txn; **unique(merchant_id, idempotency_key)**; **optimistic `version` column** ([ADR-0010](adrs/0010-payment-optimistic-concurrency.md)) |
| Concurrent payment mutation (webhook vs. reconciliation sweep vs. refund) | strong | optimistic version check; row lock on transition | stale-version write rejected + retried on fresh read | `version` column CAS; invalid state transitions rejected by state machine |
| Ambiguous provider timeout | resolve-before-act | serialized within the request | idempotent re-auth/lookup before any failover | downstream idempotency key + `provider_txn_ref` lookup ([ADR-0005](adrs/0005-exactly-once-capture-idempotency.md)) |
| `risk_assessment` insert | strong (local) | append-only, one row per `transaction_id` | dedup by `transaction_id` unique | Postgres txn with `risk_outbox` |
| Outbox → Kafka publish | at-least-once | `FOR UPDATE SKIP LOCKED` across relay workers | consumers dedup by `eventId` | relay marks `published_at`; idempotent consumers |
| Velocity / health / rate-limit (Redis) | **best-effort / eventual** | atomic INCR/EXPIRE | none — loss under-counts | Redis atomics; **loss = degraded scoring/routing, never wrong money state** ([ADR-0009](adrs/0009-database-per-service.md) downsides) |
| Notification delivery | eventual, effectively-once | consumer offset + dedup | `event_id` unique rejects reprocessing | `unique(event_id)` on `notification` |

Lost-update prevention is explicit on the only entity with concurrent mutators — `payment` — via the optimistic `version` column plus row locking during state transitions ([ADR-0010](adrs/0010-payment-optimistic-concurrency.md)). Every other durable write is append-only or single-writer, so write-skew does not arise.

## Schema Strategy

| Concern | Decision |
|---|---|
| Normalization posture | Normalized (3NF) within each service DB; the payment aggregate is a small parent/child tree (`payment` → `payment_attempt`, `refund`, `payment_status_history`) |
| Aggregate boundaries | `Payment` (root + attempts + refunds + status history), `RiskAssessment`, `ProviderTxnRef`, `Notification` — each the consistency boundary of its owning service |
| Key design | Surrogate **UUID** primary keys everywhere; natural keys expressed as unique constraints (`(merchant_id, idempotency_key)`, `event_id`, `transaction_id`) |
| Tenant isolation | Single logical tenant in v1; `merchant_id` present on money/risk rows as the **future multi-tenant seam** (no row-level security in v1 — PRD non-goal) |
| Soft-delete policy | **No deletes on financial records**; payments evolve by state transition. Non-money datasets use scheduled purge, not soft-delete flags (avoids soft-delete-forever) |
| Referential integrity | FK constraints **within** a service DB; **cross-service references are logical only** (e.g., `merchant_id`, `transaction_id`) — enforced in application code |
| Immutable / audit data | `payment_attempt`, `payment_status_history`, `risk_assessment`, and `*_outbox` rows are **append-only / immutable once written**; together they form the v1 decision-trail/audit surface |

## Indexing Strategy

| Dataset | Access Pattern | Serving Index | Index Type | Write Cost | Cardinality Assumption |
|---|---|---|---|---|---|
| `payment` | idempotency check | `unique(merchant_id, idempotency_key)` | composite unique | 1 write/charge | high (unique per charge) |
| `payment` | list by merchant | `(merchant_id, created_at desc)` | composite | 1 write/charge | medium |
| `payment` | reconciliation sweep of in-flight | `partial (status) where status in ('AUTHORIZING','PENDING')` | partial | negligible (few in-flight) | low |
| `payment_attempt` | trail by payment | `(payment_id)` | btree | 1–2/charge | medium |
| `idempotency_record` | retry lookup | `unique(merchant_id, key)` | composite unique | 1/charge | high |
| `payment_outbox` / `risk_outbox` | relay poll of unpublished | `partial (created_at) where published_at is null` | partial | append + clear | low (drains continuously) |
| `refund` | by payment | `(payment_id)` | btree | low | low |
| `risk_assessment` | trail lookup | `unique(transaction_id)` | unique | 1/charge | high |
| `provider_txn_ref` | reconciliation lookup | `(payment_id)`, `(provider, provider_ref)` | btree | 1/attempt | medium |
| `notification` | dedup | `unique(event_id)` | unique | 1/event | high |
| `notification` | retry scan | `partial (status) where status='FAILED'` | partial | low | low |

Every index above serves a named query in Access Patterns. No index exists without a query; risk-trend/analytics indexes are deliberately omitted (no v1 query needs them — PRD non-goal).

## Cache Architecture

| Layer | Cached Data | Source of Truth | Invalidation Trigger | TTL / Staleness Budget | Cold-cache Behavior | Stampede Protection |
|---|---|---|---|---|---|---|
| Distributed (Redis) | `risk_score_cache` | deterministic scorer output for a feature set | TTL only | 300 s | recompute (rule-based is cheap) | low risk; optional single-flight per key |
| Distributed (Redis) | `risk_velocity_counter` | the transaction stream (counters are a materialized rolling view) | sliding TTL (1 h / 24 h windows) | window length; loss under-counts | counters read as 0 → **lower risk score** (degraded, flagged) | N/A (atomic INCR) |
| Distributed (Redis) | `provider_health_window` | live provider-call outcomes | rolling window expiry | seconds | default **all providers healthy** → rely on reactive in-request failover | N/A |
| Distributed (Redis) | `ratelimit_bucket` | Redis is authoritative (ephemeral) | token refill schedule | N/A | **fail-open** (allow, log) | N/A |

`risk_score_cache`, `risk_velocity_counter`, and `provider_health_window` are read-derived caches with the sources of truth named above. `ratelimit_bucket` is authoritative ephemeral coordination state (accepted: its loss only relaxes throttling). No cache holds money state.

## Retention & Deletion

| Dataset | Retention Period | Deletion Mechanism | Archival | PII Handling | Audit / Legal Hold |
|---|---|---|---|---|---|
| `payment`, `payment_attempt`, `refund`, `payment_status_history` | indefinite in v1 (financial/audit record) | none (never hard-deleted) | none in v1; production would archive cold rows | test-mode emails only; no PAN stored (Stripe test tokens) | serves as v1 audit trail |
| `idempotency_record` | 72 h (retry window) | scheduled purge job | none | none | n/a |
| `risk_assessment` | indefinite in v1 (trail) | none in v1 | production retention policy deferred | features may include email → treat as PII in production | trail/audit |
| `payment_outbox` / `risk_outbox` | published + 7 day safety window | scheduled purge of published rows | none | none | prevents unbounded outbox ([ADR-0011](adrs/0011-bounded-event-and-outbox-retention.md)) |
| `event_log` (Kafka) | **7 days, time-based** | Kafka log retention (bounded) | none in v1 | envelope carries ids, not PAN | prevents unbounded event storage ([ADR-0011](adrs/0011-bounded-event-and-outbox-retention.md)) |
| `notification`, `delivery_attempt` | 30 days | scheduled purge | none | recipient test emails | n/a |
| Redis (`velocity`, `score_cache`, `health`, `ratelimit`) | seconds–24 h | native TTL expiry | none | none | n/a |

No dataset has undefined/accidental-infinite retention: financial records are intentionally indefinite; every other dataset has a bounded purge or TTL. Real-money PII/PCI retention is a PRD non-goal and deferred.

## Migration Strategy

| Concern | Decision |
|---|---|
| Tooling | **Flyway** per service (versioned SQL migrations under each service); standardizes over the reference project's partial `schema.sql` |
| Phasing | **Expand / migrate / contract** by default — additive columns first, backfill, then remove old shape in a later release |
| Online-migration constraints | Low v1 volume + single node makes locking a non-issue; the discipline is established now so it holds at scale (no blocking rewrites, concurrent index creation) |
| Dual-write / shadow-read | Not required in v1 (no live cutover); the pattern is reserved for the future Decision-Service extraction |
| Backfill strategy | Idempotent, batched backfill scripts run as a migrate-phase step, safe to re-run |
| Rollback expectations | Roll **forward** (Flyway has no auto-down); every change backward-compatible for one release so the prior service version keeps running |
| Compatibility guarantees | Event envelope + gRPC/REST contracts are additive-only within v1; consumers tolerate unknown fields |

## Operational Readiness

| Concern | Decision |
|---|---|
| Backup cadence & restore validation | v1: nightly `pg_dump` per DB + Docker volume snapshots; **restore rehearsed at least once** into a scratch container to prove the dump is usable (untested backups are treated as no backups). Production target = managed PITR (deferred) |
| Monitoring signals | Slow queries; **lock waits / `SELECT FOR UPDATE` contention on `payment`**; deadlocks; connection-pool saturation per service; **outbox depth & relay lag**; **Kafka consumer lag** (Notification); Redis hit ratio & evictions; storage growth per DB |
| Query-performance monitoring | `pg_stat_statements` per service; alert on p95 of the charge-path queries against the 150 ms budget |
| Runbook hooks | Outbox backlog growing → relay/Kafka health check; velocity/health Redis miss spike → degraded-scoring notice; payment stuck in `AUTHORIZING` → ambiguous-timeout reconciliation runbook |

## Implementation Handoffs

### implementations/data/postgres
- Four databases (`payments`, `risk`, `provider`, `notifications`); Flyway baseline per service.
- Constraints to implement as schema: `unique(merchant_id, idempotency_key)`, `unique(transaction_id)` on `risk_assessment`, `unique(event_id)` on `notification`, `version` column on `payment`, partial indexes on `*_outbox(published_at is null)` and `payment(status)` in-flight.
- Append-only tables (`payment_attempt`, `payment_status_history`, `risk_assessment`, `*_outbox`) — no UPDATE paths except outbox `published_at`.

### implementations/data/redis
- Keyspaces + TTLs: `fraud:velocity:{window}:{email}` (1 h/24 h), `fraud:score:{hash}` (300 s), `provider:health:{provider}` (rolling), `ratelimit:{principal}` (token bucket). All must tolerate flush → degraded, not incorrect.

### backend-architecture
- The charge write is a single transaction spanning `payment` + `payment_attempt` + `idempotency_record` + `payment_outbox`; optimistic `version` + row lock govern concurrent payment mutation ([ADR-0010](adrs/0010-payment-optimistic-concurrency.md)).
- Relay pattern: `FOR UPDATE SKIP LOCKED` poll; consumers idempotent by `eventId`.

### security
- `merchant_id` is the isolation seam; no cross-tenant queries in v1 but design assumes it will become row-scoped. Provider API keys are secrets, never in app tables. No PAN stored (test tokens).

### reliability / operations
- Single-node Postgres in v1 (no replica — see Omitted sections); backup/restore rehearsal is the only durability guarantee. Outbox depth, Kafka lag, and `payment` lock contention are the primary data-layer alerts.

## ADR Index

| ADR | Title | Status | Summary |
|---|---|---|---|
| [0009](adrs/0009-database-per-service.md) | Database-per-service (Postgres) + Redis for ephemeral state | Accepted | Engine + ownership foundation this document builds on. |
| [0010](adrs/0010-payment-optimistic-concurrency.md) | Optimistic concurrency + row locking for payment state | Accepted | `version` column CAS + `SELECT FOR UPDATE` prevent lost updates across webhook/refund/sweep races. |
| [0011](adrs/0011-bounded-event-and-outbox-retention.md) | Bounded retention for outbox tables and Kafka topics | Accepted | Published-outbox purge + 7-day Kafka retention prevent unbounded event storage. |

## Omitted sections

- **Partitioning & Sharding**: omitted — v1 is portfolio-scale (single active merchant during demos); no throughput/size/tenancy constraint triggers partitioning. The future trigger would be payment-table size or per-tenant blast radius, keyed by `merchant_id`.
- **Replication & High Availability**: omitted — v1 runs single-node Postgres per service under Docker Compose with no replica (HA/K8s is a PRD non-goal). Durability rests on rehearsed backup/restore (see Operational Readiness); production target (managed PITR + Multi-AZ) is a deferred decision, not a v1 topology.
- **ER / topology diagram**: omitted — the Dataset Inventory table and Schema Strategy (aggregate boundaries) fully convey ownership and relationships at this scale; a separate ER diagram would add no information for four small, service-isolated schemas.

## Deferred Decisions

- Production retention policy for `risk_assessment` and financial records (owner: self; deadline: before any real-money path). 
- Replica/HA topology and PITR target for production (owner: self; deadline: infrastructure-platform phase).
