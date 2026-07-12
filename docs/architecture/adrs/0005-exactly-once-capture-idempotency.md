---
id: 0005
title: Exactly-once capture via idempotency key + payment state machine
status: accepted
date: 2026-07-12
supersedes: null
---

# ADR 0005: Exactly-once capture via idempotency key + payment state machine

## Context

The PRD's hardest correctness requirement is **double-charge = 0** across cross-provider failover. The dangerous case is an **ambiguous provider timeout**: Payment sends `authorize` to Stripe, the connection times out with no definitive response, and Payment cannot tell whether the charge succeeded. Naively failing over to MockPay could double-charge if Stripe actually authorized.

## Decision

Guarantee exactly-once *business* execution with three mechanisms working together:
1. **Idempotency record.** Every charge carries a merchant idempotency key stored with a unique constraint; a repeated key returns the original stored outcome without re-executing.
2. **Payment state machine.** The `Payment` aggregate transitions through explicit states (Created→Authorized→Captured→Completed / Rejected / Failed); transitions are validated and persisted in a single DB transaction alongside each `payment_attempt`.
3. **Ambiguous-timeout reconciliation.** An indeterminate provider result is treated as *unknown*, not *failed*. Before failing over, the orchestrator performs an idempotent re-authorize or lookup against the same provider (passing the downstream idempotency key) to resolve the true state; only a confirmed non-authorization triggers failover.

## Consequences

**Benefits:** no double capture even under partial failure; the trail records every attempt and its resolution; supports the PRD's zero-double-charge merge gate with integration tests.

**Downsides (required):**
- Reconciliation on ambiguous timeouts adds latency to the worst-case path.
- Correctness depends on providers honoring idempotency keys / offering a lookup; MockPay implements this, and Stripe supports idempotency keys, but a provider lacking both would weaken the guarantee.
- More code and test surface (state-transition validation, reconciliation) than a naive retry.

**Revisit when:** adding providers without idempotency support, or introducing partial captures / multi-capture flows that complicate the state machine.

## Alternatives considered

- **Blind retry/failover on any error.** Rejected: double-charges on ambiguous timeouts — the exact failure the PRD forbids.
- **Distributed transaction (2PC) across providers.** Rejected: external providers do not participate in 2PC; impossible and unnecessary.
- **Idempotency in Redis only.** Rejected: money-state idempotency must be durable and transactional with the payment write; Redis is used only for ephemeral counters ([ADR-0009](0009-database-per-service.md)).
