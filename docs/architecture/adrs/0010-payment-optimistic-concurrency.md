---
id: 0010
title: Optimistic concurrency + row locking for payment state
status: accepted
date: 2026-07-12
supersedes: null
---

# ADR 0010: Optimistic concurrency + row locking for payment state

## Context

The `payment` row is the only durable entity in v1 with **multiple concurrent mutators**: the synchronous charge/failover loop, Stripe webhook callbacks confirming authorization/capture, the ambiguous-timeout reconciliation sweep, and refunds. Two of these updating the same payment concurrently (e.g., a webhook marks `Captured` while the sweep also resolves the attempt) risks a **lost update** or an invalid state transition. [ADR-0005](0005-exactly-once-capture-idempotency.md) guarantees exactly-once *capture* against providers; it does not, by itself, order concurrent writers of the payment record.

## Decision

Guard concurrent payment mutation with **optimistic concurrency plus targeted row locking**:
- A monotonically increasing `version` column on `payment`; every update is a compare-and-set on `version`, and a stale-version write is rejected and retried on a fresh read.
- During a state transition the handler takes `SELECT … FOR UPDATE` on the payment row, validates the transition against the state machine, writes, and commits — all in one READ COMMITTED transaction alongside `payment_attempt` and `payment_outbox`.
Append-only tables (`payment_attempt`, `payment_status_history`, `risk_assessment`, `*_outbox`) need no such guard — they have a single writer per row.

## Consequences

**Benefits:** no lost updates or illegal transitions under webhook/sweep/refund races; keeps the fast path lock-light (row lock only during the transition, not the whole request); works on plain Postgres with no extra infrastructure.

**Downsides (required):**
- Optimistic retries add a small amount of application complexity and a retry path that must be tested under contention.
- `SELECT FOR UPDATE` briefly serializes updates to a single hot payment row; acceptable at v1 scale but a contention point if one payment were updated at high frequency.
- Requires discipline: every writer must honor the version check; a rogue direct update bypasses the guard.

**Revisit when:** payment updates become high-frequency per row, or a workflow needs cross-row serialization (then consider serializable isolation or an explicit orchestration lock).

## Alternatives considered

- **SERIALIZABLE isolation for all payment writes.** Rejected: heavier contention and serialization-failure retries across the board for a race that only affects one entity; optimistic versioning is more targeted.
- **Pessimistic locking for the whole request.** Rejected: holds locks across gRPC/provider network calls, coupling lock duration to external latency — a scalability and deadlock hazard.
- **Last-write-wins (no guard).** Rejected: silently loses updates and permits invalid transitions — unacceptable for money state.
