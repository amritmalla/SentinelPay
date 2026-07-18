---
id: 0016
title: Conservative reconcile semantics for provider ambiguity
status: accepted
date: 2026-07-18
supersedes: null
---

# ADR 0016: Conservative reconcile semantics for provider ambiguity

## Context

ADR-0005 requires reconcile before failover on `AMBIGUOUS_TIMEOUT` so we never attempt a second provider while the first authorization may have succeeded. Phase 11 found that `StripeProvider.reconcile` replayed PaymentIntent create with `amount=0`, triggering Stripe idempotency errors mapped to `HARD_FAIL` — instructing `ChargeService` to fail over and risking a double capture.

## Decision

> **Reconcile is conservative; authorize may be optimistic.**

1. **Reconcile replays the original create** with identical parameters (amount, currency, capture method, confirm) under the same idempotency key — never a degraded replay.
2. **Unknown or erroring reconcile results map to `AMBIGUOUS_TIMEOUT`**, blocking failover. Only definitive non-authorization (`canceled`, `requires_payment_method`) maps to `NOT_AUTHORIZED`.
3. **`processing` maps to `AMBIGUOUS_TIMEOUT` on reconcile**, not `NOT_AUTHORIZED`.
4. **`IdempotencyException` on reconcile maps to `AMBIGUOUS_TIMEOUT` with ERROR logging** — it signals a parameter-derivation bug, not a safe-to-failover decline.
5. **Split exception mapping:** `mapAuthorizeException` vs `mapReconcileException` so asymmetry is explicit in code.
6. **Widen the port:** `PaymentProvider.reconcile(ReconcileRequest)` carries `paymentId`, `downstreamKey`, `amountCents`, and `currency` so adapters never infer params from the key alone.

## Consequences

**Benefits:** eliminates the Stripe double-charge footgun; reconcile failures fail closed (sweep/retry) instead of fail open (failover); trail and metrics semantics unchanged.

**Downsides:** more `AMBIGUOUS_TIMEOUT` outcomes under provider/API instability — acceptable because the reconciliation sweep and idempotent charge retry are the intended recovery path.

## Alternatives considered

- **Map all Stripe errors to HARD_FAIL on reconcile.** Rejected: converts unknowns into unsafe failover.
- **Retrieve-only reconcile without idempotent replay.** Deferred as secondary; replay is primary because it is synchronous and does not depend on search indexing lag.
