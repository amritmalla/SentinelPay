# ADR-0017: Dashboard surface and merchant trail redaction

Status: accepted · Date: 2026-07-18

## Context

Phase 12 adds a role-switched dashboard over existing payment and routing data. The full
`DecisionTrail` (routing policy, bandit posteriors, breaker states, risk factors, model version)
is OPS-only by design. Merchants need a payment outcome view without provider economics or
routing internals.

## Decision

1. **Separate merchant DTO** — `GET /api/v1/payments/{id}/summary` returns `PaymentSummary`
   (risk band + recommendation + coarse outcome), not a filtered copy of `DecisionTrail`.
   New OPS fields on the trail cannot leak to merchants by accident.

2. **Ops routing endpoints in payment-service** — Live breaker/bandit/health state lives in
   payment-service Redis, not provider-service. Add `GET /api/v1/ops/routing/providers` and
   `/config`, gateway route `id: ops`, and `OPS` authority on `/api/v1/ops/**`.

3. **Dashboard delivery** — Separate `dashboard/` container (nginx static) on port **5173**,
   calling the gateway API. Gateway `dev` profile enables CORS for `http://localhost:5173`.
   JWT stored in React memory only (no `localStorage`).

4. **Authority** — UI role switching is UX only; gateway + service matchers enforce MERCHANT vs
   OPS. Boundary tests: MERCHANT on `/trail` → 403; summary payload field-level assertions.

## Consequences

- Merchants never see routing/risk internals through the supported API surface.
- Ops dashboard reads the same stores as the charge path; Redis down → `degraded: true` (200).
- Compose demo adds one more service; CI adds a `dashboard` job (lint, typecheck, test, build).
