---
id: 0002
title: Synchronous critical path, asynchronous fan-out
status: accepted
date: 2026-07-12
supersedes: null
---

# ADR 0002: Synchronous critical path, asynchronous fan-out

## Context

The target-architecture docs (`docs/02-architecture/event-catalog.md`, service specs) model the payment decision as a **Kafka choreography**: Payment publishes `PaymentRequested`, the Decision Service consumes it and publishes `PaymentDecisionCreated`, which Payment then consumes. That is an asynchronous, multi-hop flow.

The PRD requires **in-request failover** — "retries a fallback provider *within the same request, returning one final result*." A merchant's `POST /charge` must block until there is a final authorize/capture outcome. An async event round-trip cannot deliver a single synchronous response without reintroducing request/response correlation over a bus (complex, slow, and error-prone).

## Decision

Run the **critical path synchronously**: API Gateway → Payment → (gRPC) Risk → in-process decision/routing → (REST) Provider authorize/capture → response. **Reserve Kafka for after-the-fact fan-out** only: notifications, decision-trail/audit events, and future analytics. No consumer sits on the synchronous charge path.

## Consequences

**Benefits:** satisfies the PRD's single-request failover; lower latency and simpler reasoning on the money path; matches the reference PayGuard (gRPC fraud on the critical path, Kafka async downstream); still demonstrates event-driven architecture via the fan-out.

**Downsides (required):**
- The synchronous path couples Payment's availability to Risk and Provider availability during the request; mitigated by deadlines, a circuit breaker, and the risk fallback scorer.
- Diverges from the documented target choreography, so the future migration to a standalone async Decision Service is a real (documented) change, not a no-op.
- Synchronous calls hold a request thread for the duration of failover; bounded by per-provider timeouts.

**Revisit when:** the platform grows long-running or human-in-the-loop decision steps (e.g., manual review) that genuinely need async orchestration.

## Alternatives considered

- **Full async choreography (as in the target docs).** Rejected for v1: cannot return one synchronous result for in-request failover without request/response-over-bus complexity.
- **Hybrid: async decision with client polling / webhooks.** Rejected: pushes latency and complexity onto the merchant integration and contradicts the "one final result" requirement.
