---
id: 0004
title: Fold decisioning, routing & failover into the Payment Service for v1
status: accepted
date: 2026-07-12
supersedes: null
---

# ADR 0004: Fold decisioning, routing & failover into the Payment Service for v1

## Context

The target architecture defines a standalone **Decision Service** that selects providers and drives routing/failover, separate from the **Payment Service** that executes. The PRD's #1 success metric is **double-charge = 0** across failover. The failover loop and the idempotency guard are the same critical section: deciding to retry provider B and ensuring provider A did not already capture must be reasoned about together.

Splitting decision from execution across a network boundary turns that critical section into a distributed coordination/saga problem — exactly where correctness is least forgiving — for a solo v1 build.

## Decision

For v1, **realize the Payment Decisioning & Routing bounded context as a module inside the Payment Service runtime.** Payment calls Risk over gRPC, applies the approve/review/block gate, selects the provider, and runs the failover loop — all within one process and one database transaction boundary. Decisioning & Routing remains a *named bounded context* (the future split seam), but is not a separate deployable in v1.

## Consequences

**Benefits:** idempotency + failover live in one process and one DB transaction, the safest way to guarantee exactly-once capture; fewer moving parts on the critical path; no cross-service saga in v1.

**Downsides (required):**
- Diverges from the north-star topology; a future extraction of the Decision Service is a real migration (interface + data ownership split), documented as deferred.
- The Payment Service carries two responsibilities (decide + execute), reducing that separation-of-concerns showcase in v1.
- Routing config lives in the payments DB for now, not a dedicated Decision store.

**Revisit when:** decisioning grows independently (policy engine, cost/ML routing, multiple consumers of decisions) or needs its own scaling/release cadence — then split per the target docs.

## Alternatives considered

- **Separate Decision Service (sync REST from Payment).** Rejected for v1: adds a network hop inside the idempotency-critical failover loop, raising double-charge risk and operational cost. This is the "Fuller topology" option the owner explicitly declined.
- **Decision Service via async events.** Rejected: incompatible with in-request failover ([ADR-0002](0002-synchronous-critical-path.md)).
