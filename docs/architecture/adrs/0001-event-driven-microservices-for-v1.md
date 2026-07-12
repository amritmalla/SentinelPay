---
id: 0001
title: Event-driven microservices for v1 (over modular monolith)
status: accepted
date: 2026-07-12
supersedes: null
---

# ADR 0001: Event-driven microservices for v1 (over modular monolith)

## Context

The PRD scopes a single v1 wedge (risk evaluation + routing/failover). At v1 traffic, standard architecture guidance — and this project's own system-design skill — would default to a **modular monolith**: fewer deployables, no network failure surface on the critical path, trivial idempotency.

Two PRD constraints push the other way and are explicit, not assumed:
1. The project's stated goal is a **portfolio demonstration of distributed-systems / backend engineering** (Goal: portfolio-first, venture-plausible).
2. The PRD mandates **reuse of the existing stack** — Spring Boot, Postgres-per-service, Redis, Kafka, gRPC — which is already a microservices monorepo.

A decision is needed now because it determines the number of deployables, the transport between them, and every downstream implementation skill.

## Decision

Build v1 as **event-driven microservices**: five services (API Gateway, Payment, Risk, Provider, Notification) with a **synchronous gRPC/REST critical path** and **asynchronous Kafka fan-out**. Distribution is adopted to exercise service boundaries, typed RPC, and event choreography — the portfolio objective.

## Consequences

**Benefits:** demonstrates real service boundaries, gRPC, DB-per-service, and Kafka; maps directly onto the north-star platform; lets Risk and Provider evolve/scale independently; matches the existing repo.

**Downsides (required):**
- Network hops on the critical path add latency and new failure modes (timeouts, partial failure) that a monolith would not have.
- Exactly-once execution is harder across processes; we must invest in idempotency + reconciliation ([ADR-0005](0005-exactly-once-capture-idempotency.md)).
- Higher operational burden for a solo developer: 5 services + Postgres×4 + Redis + Kafka to run and observe.
- Genuine risk of a **distributed monolith** if services become chatty; mitigated by folding decisioning into Payment ([ADR-0004](0004-fold-decisioning-into-payment.md)) and keeping the sync path shallow.

**Revisit when:** if this were pursued as a real product, or the operational cost outweighs the demonstration value, collapse to a modular monolith (a major, bounded-context-changing revision).

## Alternatives considered

- **Modular monolith (single deployable, module boundaries).** Rejected *only* because it does not exercise the distributed patterns the portfolio goal requires — it would otherwise be the correct choice at this scale, and this is stated openly.
- **Full 10-service target topology now.** Rejected as over-engineering for a v1 wedge and unfinishable solo; deferred as the north-star (see PRD Out of scope).
- **Serverless functions.** Rejected: complex domain core (payment state machine, failover) and local-debuggability needs make FaaS a poor fit.
