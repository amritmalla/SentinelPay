---
product: sentinelpay
status: approved
owner: self
version: 0.3.0
last_reviewed: 2026-07-12
---

# PRD — SentinelPay (v1: Resilient Multi-Provider Routing & Failover)

> **Product identity:** SentinelPay is an **Adaptive Payment Intelligence Platform** — an intelligence/decision layer between merchants and payment providers (full vision in `docs/product/vision/`). That identity is the north star and does not change.
>
> **What this PRD scopes:** the platform's **first capability (v1)** — resilient multi-provider routing & failover. This is the wedge the broader intelligence platform grows from (adaptive routing, risk intelligence, cost optimization, and decision observability all build on the same routing + decision-trail spine). The wider vision is preserved under **Out of scope (future)** and sequenced after v1, not built now.

## Problem

A **Payment Operations Specialist** at a merchant whose revenue depends on card payments is measured on authorization rate and incident response. When their payment provider degrades or hard-fails a transaction, there is no automatic recovery: the charge simply fails, the customer abandons, and revenue is lost. The specialist typically learns about it after the fact — through a dashboard dip or customer complaints — then investigates manually and has no fast, safe way to shift traffic to an alternative provider. Single-provider dependency turns every provider hiccup into uncontrolled, invisible revenue loss.

## Why Now

Merchants increasingly run more than one payment provider, but most integrations still hard-wire a single provider into the checkout path, so provider outages and elevated decline rates translate directly into lost sales with no automated fallback. The pain is concrete and recurring (every provider incident), not a speculative future trend — which is what makes failover a defensible first workflow rather than a nice-to-have.

## Users

- **Primary (daily): Payment Operations Specialist.** Owns approval rates and payment incident response. Lives in dashboards and alerts; needs to keep authorization high and explain what happened when it dips.
- **Secondary (setup only): Integrating Engineer / Merchant Administrator.** Performs the one-time integration and configures provider priority and routing rules. Not a v1 focus beyond initial configuration.

## JTBD

> "When my primary payment provider fails or degrades, automatically recover the payment through another provider — without ever double-charging the customer — and show me why each payment went where it did, so I keep authorization rates high and can explain every incident."

## Current Alternatives

- **Single-provider integration with hand-coded retry** (or no retry): on failure the merchant app gives up or retries the *same* failing provider; ops manually toggles config or contacts the provider. Slow, manual, revenue already lost.
- **Full orchestration incumbents** (Primer, Spreedly, Gr4vy, Cybersource): capable but heavy to integrate, expensive, and aimed at large merchants — overkill for a team that just wants automatic failover.
- **Manual incident response**: provider dashboards + spreadsheets + Slack; reactive and after-the-fact.

## Scope

Five v1 outcomes, structured around the decision spine (evaluate → decide → execute → explain). Each names the actor(s) performing the step.

1. **Risk evaluation pipeline (Intelligence First).** For each charge, a staged, pluggable evaluation pipeline **(risk pipeline, system)** assembles features and produces an **explainable risk assessment** — normalized score + contributing factors + recommendation. v1 uses a rule-based scorer behind a Strategy interface (ONNX-ready seam); it evaluates risk but does not itself make the final business decision. Modeled on a rule-based fraud-scoring engine's rule set and staged orchestrator (design captured in [ADR-0007](../architecture/adrs/0007-pluggable-risk-scorer-strategy.md)).
2. **Decision + in-request cross-provider failover.** The **decision/routing service (system)** consumes the risk recommendation to gate the payment (approve / review / block), then routes approved charges to a healthy provider and, on failure/timeout, retries a fallback provider **within the same request**, returning one final result. Caller: **merchant app**.
3. **Guaranteed no double-charge.** The **system** enforces exactly-once capture across failover via an idempotency key and an explicit transaction state machine, even when a provider times out ambiguously.
4. **Explainable decision trail.** For every payment the **system** records the risk factors *and* which providers were attempted, why each was chosen or skipped, and the outcome; the **Payment Ops Specialist** can query this end-to-end trail per transaction.
5. **Live health + configuration over code.** A **health-monitor (system)** continuously tracks per-provider success rate, latency, and error types and feeds routing; the **Payment Ops Specialist** can see live approval-rate/health and disable or re-prioritize a provider **or toggle a risk rule without a code deploy**; the **Integrating Engineer / Merchant Admin (setup)** defines provider priority, routing conditions, and the active rule set through configuration.

## Non-goals

- **No trained ML model, cost-optimized routing, or predictive scoring in v1.** The risk pipeline and routing are rule- and health-based. The pipeline exposes a pluggable scorer seam (ONNX-ready), but training, evaluation, and cost/success-rate prediction are deferred — they add data/eval burden without first proving the pipeline + failover spine. *(Deferred, not rejected.)*
- **No live card traffic, PCI-DSS scope, or real money movement/settlement.** Sandbox/test providers only. Real compliance is a multi-quarter effort orthogonal to demonstrating the wedge.
- **No advanced risk signals in v1.** The risk pipeline uses only the reference transaction/amount/velocity rule set (some features may be stubbed). Device fingerprinting, IP/network reputation, behavioral biometrics, and customer/device risk profiles are out of scope — they require real external signal sources and would balloon the wedge. The pipeline's Strategy interface is designed to accept them later.
- **No multi-tenant SaaS control plane** (org/user management, billing, RBAC). A single logical merchant/tenant in v1; multi-tenancy is platform scope earned later.
- **No second *real* provider integration** (e.g., Adyen). A controllable simulated provider proves failover deterministically; real second-provider onboarding is deferred.

## Constraints

- Solo developer on a portfolio timeline; the whole flow must be demoable end-to-end on a laptop.
- Sandbox/test providers only: Stripe test mode + a controllable simulated provider.
- Reuse the existing stack — Java 17, Spring Boot 3.2, PostgreSQL, Redis, Kafka, gRPC — rather than rebuilding.
- Must run locally via Docker Compose with no cloud dependency required to demo.
- **Provider set (decided 2026-07-11):** exactly two providers in v1 — Stripe (test mode) and a controllable simulated provider ("MockPay") exposing the *same* provider interface, with on-demand failure/latency injection. No real second provider in v1.
- **Risk pipeline (decided 2026-07-12):** the rule-based fraud check is promoted into a staged, pluggable **risk-evaluation pipeline** (Strategy-pattern scorer, ONNX-ready seam, explainable output) modeled on the reference fraud engine. It *evaluates* risk and produces a recommendation; a separate decision step *decides*. Scorer stays rule-based in v1; a simple/imperfect implementation is acceptable (portfolio priority is demonstrating the pipeline pattern, not scoring accuracy).

## Assumptions

- Failover is convincingly demonstrable with one real provider (Stripe test mode) plus a controllable simulated provider that injects failures/latency on demand.
- Idempotent cross-provider retry can be implemented correctly with an idempotency key + transaction state machine such that no double-capture occurs, even on ambiguous timeouts.
- A Payment Ops persona judges value primarily on recovered-authorization-rate and decision explainability.
- Hiring reviewers value one deeply-correct wedge (idempotent failover) over broad-but-shallow platform coverage.
- For v1 the risk pipeline's value is carried by its *structure* — staged, pluggable (Strategy), explainable, ONNX-ready — not by scoring accuracy; stubbed/hardcoded features (as in the reference engine's TODOs) are acceptable.

## Risks

- **Risk:** Double-charge / duplicate capture during cross-provider retry.
  **Why it matters:** It is the single most important correctness property; one visible double-charge destroys credibility with reviewers and users alike.
  **Mitigation:** Idempotency key + explicit transaction state machine; Testcontainers integration tests asserting exactly-once capture across every failover path; treat double-charge rate = 0 as a merge gate.
- **Risk:** The simulated provider reads as "fake," undercutting venture-plausibility.
  **Why it matters:** Reviewers may discount the failover story if the second provider is obviously a stub.
  **Mitigation:** Make the simulator implement the *same* provider interface as the Stripe adapter, with configurable latency/error injection; document that a real provider drops into the same port with no routing changes.
- **Risk:** Scope creep back toward the full "payment intelligence platform" vision.
  **Why it matters:** The vision docs are seductive; breadth kills completion, which is the worst portfolio outcome.
  **Mitigation:** The Non-goals above; the grand vision lives only under Out of scope (future).
- **Risk (venture lens):** No real adoption path while sitting in the money path (the trust/compliance wall).
  **Why it matters:** Limits "something meaningful" without heavy PCI/partnership investment.
  **Mitigation:** Portfolio-first framing. If pursued for real, the *out-of-money-path observability* variant is the lower-trust entry wedge — recorded under Out of scope (future) as the pivot option.

## Distribution and Adoption

Portfolio-first, so the primary "consumers" are (a) a hiring reviewer evaluating the running system and (b) a merchant application at the integration point. Framed as internal integration + staged cutover:

- **Integration point:** a merchant app changes its charge call from "call Stripe directly" to "call SentinelPay," and SentinelPay calls the underlying providers.
- **Staged rollout (also the demo narrative):** (1) *shadow mode* — SentinelPay records routing decisions without being in the path; (2) *single-provider passthrough* — in path, one provider, no failover; (3) *failover enabled* — health-driven routing + automatic fallback.
- **Realistic first-adopter wedge (if pursued for real):** merchants who *already run two providers* and want automatic failover between them — far narrower and more credible than "every merchant."

## Success Metrics

| Metric | Unit | Target | Timeframe |
|---|---|---|---|
| Recovered authorization rate (charges that fail on primary but succeed via failover when a healthy fallback exists) | % of recoverable failures | ≥ 95% | Demonstrated in v1 chaos-test suite |
| Double-charge rate under failover | duplicate captures per 10k simulated failover events | 0 | v1 integration test suite (merge gate) |
| Added routing+failover overhead vs. direct provider call | ms, p95 (excluding provider time) | ≤ 150 ms | v1 performance test |
| Decision-trail coverage | % of processed payments with a complete, queryable trail (risk factors + routing attempts + outcome) | 100% | v1 |

## Open Questions

None open. The two prior open questions were resolved on 2026-07-11 with the recommended defaults and moved to **Constraints** (provider set = Stripe test mode + simulated "MockPay"; fraud = minimal in-path pass/block gate).

## Out of scope (future)

Preserved from the broader vision in `docs/product/vision/`, explicitly deferred past v1:

- Cost-optimized and success-rate-predictive (ML) routing.
- Adaptive/behavioral fraud & risk intelligence as a first-class engine.
- Multi-tenant SaaS control plane (organizations, users, RBAC, billing).
- Additional real providers and regional/currency-aware routing.
- Out-of-money-path **payment decision observability** product (candidate real-world pivot with a lower trust barrier).
- Executive/business-intelligence dashboards and analytics.

## Omitted sections

- None. All conditional sections (Why Now, Current Alternatives, Risks, Distribution and Adoption, Out of scope) are present and material.
