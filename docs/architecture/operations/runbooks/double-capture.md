# Double capture runbook

## Symptom

`DoubleCaptureDetected` fired — `sentinelpay_double_capture_total` increased. This is a **critical correctness invariant**: the counter must remain 0.

## Dashboard

Grafana → **Correctness** → double capture stat (green at 0, red if >0).

## First checks

1. Query recent payments that reached `CAPTURED`/`COMPLETED` twice — audit `payment_attempt` rows.
2. Review reconciliation sweep and webhook handlers for duplicate capture paths.
3. Open Tempo trace for the affected `sentinelpay.correlation_id` if known.
4. Check provider capture counts vs internal attempt outcomes.

## Likely causes

- Race between in-request capture and reconciliation/webhook completion
- Idempotency key collision across merchants (should be impossible with scoping — verify)
- Bug in `PaymentTransactionService.completePayment` guard bypass

## Rollback

Stop traffic to payment-service immediately if counter keeps increasing. Disable reconciliation sweep and webhooks until root cause is isolated. Engage finance/ops for affected payment IDs.
