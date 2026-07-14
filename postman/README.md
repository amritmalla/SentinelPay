# Postman / Newman API tests

End-to-end API tests for the SentinelPay gateway, covering the charge lifecycle, idempotency
(no double-charge), refunds, the decision trail, risk assessment, webhooks, and authz rejections.

## Files

| File | Purpose |
| --- | --- |
| `SentinelPay.postman_collection.json` | The requests + test assertions |
| `SentinelPay.local.postman_environment.json` | Local variables (`base_url`, tokens, ids) |

## Prerequisites

1. Dependencies up: `docker compose up --build` (full stack) or `make up-infra` (IDE dev).
2. For Newman/Postman only: gateway on the `dev` profile (included automatically in full-stack compose).

## Run in Postman

Import both files, select the **SentinelPay — Local** environment, then run the collection with the
**Collection Runner** (top to bottom — later requests reuse the `payment_id` captured by the charge).

## Run headless with Newman

```bash
npx newman run postman/SentinelPay.postman_collection.json \
  -e postman/SentinelPay.local.postman_environment.json
```

Exit code is non-zero if any assertion fails, so this works as a CI/API smoke check.

## What it verifies

| Folder | Checks |
| --- | --- |
| 0. Auth | `/dev/token` mints MERCHANT and OPS JWTs (captured for reuse) |
| 1. Charge | 201 + `status=COMPLETED`, `provider=MOCKPAY`; **idempotent replay returns the same payment**; invalid body is 4xx |
| 2. Query | list + get return the charged payment (merchant-scoped) |
| 3. Refund | 201, refund is bound to the payment |
| 4. Ops | decision trail + fraud assessment return 200 with an OPS token |
| 5. Webhooks | Stripe webhook is accepted (204), unauthenticated |
| 6. AuthZ | no token → 401; MERCHANT token on an OPS endpoint → 403 |

## Notes

- The run is self-seeding: `merchant_id` and `idempotency_key` are generated on first use, so no manual
  setup is needed.
- Tokens are minted via the gateway's `dev`-profile endpoint; there is no `/dev/token` outside `dev`.
- `provider=MOCKPAY` reflects the default routing (MockPay primary; the Stripe slot is stubbed unless
  `STRIPE_MODE` is changed).
