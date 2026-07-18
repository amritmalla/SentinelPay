---
id: 0014
title: Per-provider circuit breaking with Resilience4j
status: accepted
date: 2026-07-17
supersedes: null
---

# ADR 0014: Per-provider circuit breaking with Resilience4j

## Context

Reactive failover still attempts a known-degraded primary on every charge until the attempt fails. Phase 10 adds a breaker stage so providers in sustained failure are removed from the candidate set before authorization is attempted. Correctness invariants (idempotency, ambiguous-timeout reconciliation, state machine) must not depend on breaker behavior.

## Decision

1. **Resilience4j `CircuitBreaker` per provider**, driven programmatically from attempt outcomes (not Spring AOP). Micrometer binder exports state transitions for dashboards.
2. **The never-strand guardrail:** if every eligible provider's breaker is OPEN, skip breaker filtering entirely, mark the decision `BREAKERS_BYPASSED`, and walk the full ranked order. The existing failover loop remains the safety net.
3. **Half-open probes are real charges.** No synthetic probe traffic. HALF_OPEN providers are pinned **last** among admitted candidates so probes happen only when the charge would reach them anyway.
4. **`AMBIGUOUS_TIMEOUT` counts as failure** for breaker and health purposes — it consumed provider budget even when reconciliation may later recover.
5. **Breaker state is visible in the trail** (`breaker_state` on each provider rationale) and in logs/metrics on transition.

## Consequences

**Benefits:** doomed provider calls are skipped after a small failure window; mature library + free metrics; guardrail preserves ≥95% recovered-auth behavior when all breakers are open.

**Downsides:** tuning window/threshold/minimum-calls per environment; half-open recovery adds narrative complexity in demos; breaker state is in-memory per instance (acceptable at demo scale).

## Alternatives considered

- **Hand-rolled breaker.** Rejected: Resilience4j is battle-tested and exports Micrometer gauges.
- **Remove provider permanently on open breaker.** Rejected: violates never-strand and blocks recovery probes.
