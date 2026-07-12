---
id: 0006
title: Provider abstraction with a controllable simulated provider (MockPay)
status: accepted
date: 2026-07-12
supersedes: null
---

# ADR 0006: Provider abstraction with a controllable simulated provider (MockPay)

## Context

The wedge is cross-provider failover, which needs at least two providers behind a common interface. Integrating a second *real* provider (e.g., Adyen) is high-effort and — critically — cannot be made to fail *on command*, so failover could not be demonstrated deterministically. The PRD decided (2026-07-12) on Stripe test mode + a controllable simulated provider.

## Decision

Define a single `PaymentProvider` interface (`authorize`, `capture`, `refund`, health) with two adapters:
- **StripeAdapter** — Stripe test mode (real sandbox integration).
- **MockPayAdapter** — a simulator implementing the *same* interface with **injectable failure, latency, and error-class** behavior via configuration/request hints.

Adapters normalize provider outcomes into a common taxonomy: `authorized`, `hard_fail`, `retryable`, `ambiguous_timeout`. Routing/failover operate only on the normalized taxonomy, never on provider-specific codes.

## Consequences

**Benefits:** failover is **deterministically demonstrable** (toggle MockPay to fail → watch traffic recover on the other provider) — directly enabling the PRD's recovered-authorization metric and chaos tests; real providers drop into the same interface later with no routing changes; keeps Stripe as a genuine integration.

**Downsides (required):**
- A reviewer may discount MockPay as "not a real provider"; mitigated by holding it to the same interface and documenting the drop-in path.
- Maintaining the simulator is extra code that ships no real capability.
- The common error taxonomy may not capture every real provider nuance until a second real provider is added.

**Revisit when:** integrating a second real provider for the venture path — MockPay then becomes a test double rather than a runtime provider.

## Alternatives considered

- **Two real providers (Stripe + Adyen).** Rejected for v1: high integration cost and, decisively, cannot inject failures on demand for a reliable failover demo. Deferred (PRD Out of scope).
- **Single provider with simulated error responses inline.** Rejected: no real interface boundary, so it fails to demonstrate provider abstraction — the core of "provider agnostic."
