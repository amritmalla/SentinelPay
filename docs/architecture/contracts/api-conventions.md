# API Conventions — SentinelPay Public API (v1)

Companion to [openapi.yaml](openapi.yaml). Records the per-service API and naming choices left to the individual service.

**Spec location:** centralized under `docs/architecture/contracts/openapi.yaml` for the architecture phase. On implementation it co-locates with the gateway/payment service as `<service>/api/openapi.yaml`; the spec remains the source of truth (generate stubs/clients from it).

## Resource Naming

- Plural, lowercase, kebab-case nouns: `/payments`, `/fraud-assessments`, `/reconciliation-runs`, `/providers/health`.
- Sub-resources reflect ownership, max depth 2: `/payments/{payment_id}/refunds`, `/payments/{payment_id}/trail`.
- **No verbs in paths.** State-changing actions are `POST`s to sub-resource collections (`/payments/{id}/refunds`, `/reconciliation-runs`).
- Identifiers are UUIDv4 path params in snake_case (`payment_id`, `transaction_id`).
- Payload keys are **snake_case** (backend surface, per naming-conventions).

## HTTP Semantics

- `POST /payments` **creates a Payment and returns 201** with the outcome in `status` (`COMPLETED`, `BLOCKED`, `IN_REVIEW`, `FAILED`). A risk block is a business outcome, **not** `403`. `502 UPSTREAM_UNAVAILABLE` is reserved for a genuine dependency outage where no provider/fallback could serve the request.
- `GET` reads are safe and cacheable-by-id.
- No `PUT`/`PATCH` in v1 — payments are not mutated by clients; they evolve by server-side state transition. If partial update is added later it will use **RFC 7396 JSON Merge Patch** (`application/merge-patch+json`), documented here at that time.
- No hard delete (financial records are retained; ADR-0011 governs event/outbox retention only).
- Optimistic concurrency is server-internal (payment `version`, ADR-0010); clients do not send version headers in v1.

## Pagination

- Cursor-based, default on `GET /payments`: response `{ data: [...], page: { next_cursor, limit } }`.
- `limit` bounded 1–100, default 50; larger → `400 VALIDATION_FAILED`.
- Deterministic ordering: `created_at DESC, payment_id DESC`. Cursors are opaque and stable (not base64 offsets).

## Filtering and Sorting

- `GET /payments` filters: `status` (enum). Date-range filters may be added additively.
- Default sort is newest-first; no client-selectable sort in v1 (avoids filter/sort explosion).

## Idempotency

- **Required** on every unsafe money operation: `POST /payments`, `POST /payments/{id}/refunds`, `POST /reconciliation-runs`.
- `Idempotency-Key` header, **UUIDv4**, scoped `(merchant_id, key)`, **72-hour** retention (payment retry window; longer than the generic 24 h default because charge retries can span provider incidents).
- Duplicate, completed key → **original resource** returned (200/201 as first time).
- Same key, **different body** → `409 IDEMPOTENCY_CONFLICT`.
- In-flight duplicate → `409 CONFLICT` (`charge_in_progress`) — client retries after backoff.

## Error Contract

Shared envelope on every non-2xx:

```json
{ "error": { "code": "VALIDATION_FAILED", "message": "…client-safe…", "details": [ { "field": "amount_cents", "issue": "must be > 0" } ], "request_id": "…" } }
```

- `request_id` equals the `X-Correlation-Id` propagated across services — the same id appears in logs and traces.
- `message` is client-safe; no stack traces or persistence-layer leakage.

### Error Code Registry

| Code | HTTP | Meaning | Notes |
|---|---:|---|---|
| `VALIDATION_FAILED` | 400 | Schema/parameter validation failed | Per-field `details` |
| `UNAUTHENTICATED` | 401 | Missing/invalid JWT | |
| `FORBIDDEN` | 403 | Authenticated but not permitted | Not used for risk blocks |
| `RESOURCE_NOT_FOUND` | 404 | Payment/assessment not found (or not visible) | |
| `CONFLICT` | 409 | Illegal state transition / charge in progress | |
| `IDEMPOTENCY_CONFLICT` | 409 | Same key, different body | |
| `REFUND_EXCEEDS_CAPTURED` | 422 | Refund > remaining captured (domain) | |
| `RATE_LIMITED` | 429 | Rate limit exceeded | `Retry-After` + `X-RateLimit-*` |
| `INTERNAL` | 500 | Unhandled error | `request_id` required |
| `UPSTREAM_UNAVAILABLE` | 502 | No provider/fallback available | Distinct from a `FAILED` payment |

Business outcomes (`BLOCKED`, `IN_REVIEW`, `FAILED`) are **payment states**, not errors — they return 201 with the Payment resource.

## Tags and Operation Naming

- Tags = plural resource names: `payments`, `fraud-assessments`, `providers`, `reconciliation-runs`, `webhooks`.
- `operationId` in camelCase, globally unique, stable: `createPayment`, `getPayment`, `listPayments`, `createRefund`, `getPaymentTrail`, `getFraudAssessment`, `getProviderHealth`, `createReconciliationRun`, `receiveStripeWebhook`.

## Versioning and Compatibility

- URI versioning: `/api/v1`. Additive changes (new fields, endpoints, optional params) do **not** bump the version.
- Breaking changes → `/api/v2`; two prior majors supported ≥6 months after a deprecation announcement.
- Event schemas are versioned by the producing service; consumers tolerate unknown fields.

## Security

- Public + ops endpoints require `bearerAuth` (JWT, HS256) validated at the API Gateway; identity propagated downstream via `X-User-Id`/`X-Correlation-Id`.
- Authorization: merchants are scoped to their own payments; ops endpoints (`fraud-assessments`, `providers/health`, `reconciliation-runs`) require an ops role.
- `POST /webhooks/stripe` is `security: []` (provider-signed). **Signature verification is deferred in v1** (PRD non-goal) — flagged for the security review before any real-money path.
- No credentials in query strings. No PAN handled (Stripe test tokens). Provider API keys are secrets.

## Rate Limiting

- Token-bucket at the gateway: **10 req/s sustained, burst 20**, scoped per authenticated user (fallback per IP). Declared per-operation via `x-rate-limit`.
- Every response carries `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`; `429` includes `Retry-After`.

## Examples

See inline `example`s in [openapi.yaml](openapi.yaml) (charge request/response, error envelopes). All examples satisfy their schemas (positive `amount_cents`, ISO-4217 currency, ISO-8601 UTC timestamps, UUID ids).

## Deferred Decisions

- Stripe webhook signature verification (deferred per PRD; recommended before real money).
- Separate `authorize`/`capture` endpoints (v1 charge combines them).
- Client-facing optimistic-concurrency headers (`If-Match`/ETag) if client-driven updates are introduced.
- Spectral/Redocly lint gate wiring into CI (lint run locally at authoring time; CI gate is an implementation-phase task).
