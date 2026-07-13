# SentinelPay Security Review (Phase 5)

This document captures the v1 security posture after Phase 5: authentication at the gateway,
authorization in services, gateway-trust between edge and backends, and Stripe webhook signature
verification.

## Authentication model

- **Boundary:** The API Gateway is the only internet-facing HTTP entry point.
- **Token format:** HS256 JWT signed with `JWT_SECRET` (env: `sentinelpay.security.jwt-secret`).
- **Claims:**
  - `sub` — merchant UUID (tenant identity for merchant-scoped endpoints)
  - `role` — `MERCHANT` or `OPS`
- **No login service in v1.** Tokens are issued externally; a `@Profile("dev")` helper at
  `POST /dev/token` mints short-lived tokens for local demos only.
- **Validation:** Spring OAuth2 Resource Server on the gateway rejects missing/invalid tokens with
  **401** before routing.

## Authorization model (default-deny)

| Path pattern | Required authority |
|---|---|
| `POST /payments/charge`, refunds, `GET /payments`, `GET /payments/{id}` | `MERCHANT` (own data) |
| `GET /payments/{id}/trail`, `/fraud-assessments/**`, `/providers/**`, `/reconciliation-runs/**` | `OPS` |
| `POST /webhooks/**` | none (Stripe signature); gateway secret still required downstream |
| Actuator `health` / `info` | open |

Unmapped paths return **403** at the gateway and **denyAll** in services.

### Merchant data scoping

Merchant endpoints derive `merchant_id` from the authenticated principal (`CurrentMerchant.id()`),
not from request bodies or query parameters. Cross-merchant access returns **404**
(`payment_not_found`) to avoid existence leakage.

## Trust boundaries

### Gateway → service identity propagation

`IdentityPropagationFilter` (gateway):

1. **Strips** inbound `X-Merchant-Id`, `X-Auth-Role`, and `X-Gateway-Secret` (anti-spoofing).
2. **Injects** `X-Gateway-Secret` on every proxied request.
3. **Injects** `X-Merchant-Id` and `X-Auth-Role` from the validated JWT when present.

Webhook routes have no JWT; they receive only the gateway secret.

### Service gateway-trust filter

`GatewayIdentityFilter` (shared in `sentinelpay-common`) on `/api/**`:

1. Requires `X-Gateway-Secret` matching `GATEWAY_SHARED_SECRET` (constant-time compare) → **401** if
   missing/wrong. This blocks direct service bypass even if an internal port is reachable.
2. When `X-Merchant-Id` is present, establishes a `PreAuthenticatedAuthenticationToken` so Spring
   Security enforces `hasAuthority("MERCHANT"|"OPS")`.

## Stripe webhooks

- `POST /api/v1/webhooks/stripe` reads the **raw body** and verifies `Stripe-Signature` via
  `Webhook.constructEvent` and `STRIPE_WEBHOOK_SECRET`.
- Invalid/missing signatures → **400**; no `processed_webhook_events` row is written.
- Valid events are deduplicated by Stripe event id (idempotent **204**).

## Secrets handling

| Secret | Source | Stored in DB/logs? |
|---|---|---|
| `JWT_SECRET` | env | no |
| `GATEWAY_SHARED_SECRET` | env | no |
| `STRIPE_API_KEY` | env | no |
| `STRIPE_WEBHOOK_SECRET` | env | no |
| DB passwords | env per service | no |

Structured logging (logback JSON) emits correlation id, merchant id, and payment id only — secrets
are not logged. Grep of the codebase confirms no logging of secret values.

## Closed in Phase 5 (previously deferred)

- Stripe webhook **signature verification** (was open since Phase 4b).

## Still deferred

- **mTLS** between internal services.
- Automated **key rotation** / secret-manager integration (Vault, AWS SM, etc.).
- **Per-tenant row-level security** in PostgreSQL (v1 uses application-layer merchant scoping).
- Real **provider-service** extraction and dedicated provider health API.
- Full **OAuth2/OIDC** issuer integration (external IdP instead of shared HS256 secret).

## Operational notes

- Rotate `JWT_SECRET`, `GATEWAY_SHARED_SECRET`, and Stripe secrets together in coordinated deploys.
- Never expose payment/risk/provider service ports directly to the internet.
- Disable the `dev` profile (and `/dev/token`) in production.
