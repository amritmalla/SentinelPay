---
id: 0013
title: Smart routing model and charge-path health state
status: accepted
date: 2026-07-17
supersedes: null
---

# ADR 0013: Smart routing model and charge-path health state

## Context

Phase 10 replaces the hardcoded `List.of(MOCKPAY, STRIPE)` order with a four-stage routing pipeline. The charge path already observes every provider attempt (outcome + latency); routing should consume that signal without adding synchronous cross-service calls on the hot path.

Provider-service maintains its own health window for its bounded context. Routing needs the **payment-service view** of provider health — what the attempt loop actually experienced.

## Decision

1. **Health state lives in payment-service Redis**, keyed per provider with sliding success buckets and a latency EWMA. Reads are local and sub-millisecond; writes happen on the same seam as `ChargeMetrics.recordProviderAuthorization`.
2. **Fail-open on Redis errors.** Any read/write failure logs at WARN and routing falls back to the static order. Redis being unavailable must never fail a charge.
3. **Ranking demotes; only the breaker removes.** Scored and bandit policies reorder candidates but never drop them. Exclusion is reserved for eligibility rules (merchant deny, currency, max amount) and the circuit breaker stage.
4. **Every stage is explainable in the trail.** Per-provider rationale records matched rules, breaker state, score components or bandit `(α, β)` + sampled θ, and traffic-split assignment. The persisted `routing_decision` jsonb on the payment is the audit artifact.

## Consequences

**Benefits:** routing input is co-located with the attempt loop; no new gRPC hop; trail remains the single explainability surface; static order is a one-flag kill switch.

**Downsides:** health views in payment-service and provider-service may diverge; Redis adds operational dependency (mitigated by fail-open); instance-local ranking policy caches require rank-then-rationale in one thread (same pattern as scored policy).

## Alternatives considered

- **Ask provider-service for health on every charge.** Rejected: adds latency and a new failure domain on the critical path.
- **PostgreSQL-backed health windows.** Rejected for v1: write amplification on the hot path; Redis sliding buckets are a better fit.
