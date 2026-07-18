#!/usr/bin/env bash
# SentinelPay narrated demo — happy path, failover, risk block.
# Prereq: full stack up (docker compose up --build), gateway on :8080.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
MERCHANT_ID="${MERCHANT_ID:-$(uuidgen 2>/dev/null || python -c 'import uuid; print(uuid.uuid4())')}"
DEMO_EMAIL="demo-risk-$(date +%s)@example.com"

echo "▶ SentinelPay demo — gateway at ${BASE_URL}"
echo

echo "▶ Setup: minting MERCHANT token…"
TOKEN=$(curl -sf "${BASE_URL}/dev/token" \
  -H 'Content-Type: application/json' \
  -d "{\"role\":\"MERCHANT\",\"merchant_id\":\"${MERCHANT_ID}\"}" | jq -r .token)
OPS_TOKEN=$(curl -sf "${BASE_URL}/dev/token" \
  -H 'Content-Type: application/json' \
  -d '{"role":"OPS","merchant_id":"00000000-0000-0000-0000-000000000001"}' | jq -r .token)
echo "   merchant_id=${MERCHANT_ID}"
echo

echo "▶ Act 1 — Happy path: charge \$25 via MockPay…"
HAPPY=$(curl -sf "${BASE_URL}/api/v1/payments/charge" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-happy-1' \
  -H 'X-Correlation-Id: demo-corr-happy' \
  -d '{"amount_cents":2500,"currency":"USD","customer_email":"buyer@example.com","merchant_category":"retail","card_country":"US"}')
echo "${HAPPY}" | jq .
PAYMENT_ID=$(echo "${HAPPY}" | jq -r .payment_id)
STATUS=$(echo "${HAPPY}" | jq -r .status)
PROVIDER=$(echo "${HAPPY}" | jq -r .provider)
[[ "${STATUS}" == "COMPLETED" && "${PROVIDER}" == "MOCKPAY" ]] || {
  echo "Expected COMPLETED/MOCKPAY, got ${STATUS}/${PROVIDER}" >&2
  exit 1
}
echo "   Decision trail:"
curl -sf "${BASE_URL}/api/v1/payments/${PAYMENT_ID}/trail" \
  -H "Authorization: Bearer ${OPS_TOKEN}" | jq .
echo "   Check MailHog for the receipt: http://localhost:8025"
echo

echo "▶ Act 2 — Failover: programming MockPay → HARD_FAIL, charging again…"
curl -sf -X POST "${BASE_URL}/dev/providers/mockpay/program" \
  -H 'Content-Type: application/json' \
  -d '{"outcomes":["HARD_FAIL"]}' >/dev/null
FAILOVER=$(curl -sf "${BASE_URL}/api/v1/payments/charge" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-failover-1' \
  -H 'X-Correlation-Id: demo-corr-failover' \
  -d '{"amount_cents":2500,"currency":"USD","customer_email":"buyer@example.com"}')
echo "${FAILOVER}" | jq .
FO_STATUS=$(echo "${FAILOVER}" | jq -r .status)
FO_PROVIDER=$(echo "${FAILOVER}" | jq -r .provider)
[[ "${FO_STATUS}" == "COMPLETED" && "${FO_PROVIDER}" == "STRIPE" ]] || {
  echo "Expected COMPLETED/STRIPE (failover), got ${FO_STATUS}/${FO_PROVIDER}" >&2
  exit 1
}
echo "   Grafana → Correctness: recovered_authorization_total ↑, double_capture_total stays 0"
echo

echo "▶ Act 3 — Risk block: six high-amount charges for ${DEMO_EMAIL}…"
BLOCKED=""
for i in 1 2 3 4 5 6; do
  RESULT=$(curl -sf "${BASE_URL}/api/v1/payments/charge" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H 'Content-Type: application/json' \
    -H "Idempotency-Key: demo-risk-${i}" \
    -d "{\"amount_cents\":150000,\"currency\":\"USD\",\"customer_email\":\"${DEMO_EMAIL}\",\"merchant_category\":\"retail\",\"card_country\":\"GB\"}")
  STATUS=$(echo "${RESULT}" | jq -r .status)
  echo "   charge ${i}: ${STATUS}"
  if [[ "${STATUS}" == "BLOCKED" ]]; then
    BLOCKED="${RESULT}"
    break
  fi
done
[[ -n "${BLOCKED}" ]] || {
  echo "Expected a BLOCKED charge by the 6th attempt" >&2
  exit 1
}
BLOCKED_ID=$(echo "${BLOCKED}" | jq -r .payment_id)
echo "   Blocked payment trail (amount + velocity; card_country=GB vs merchant default US for geo signal):"
curl -sf "${BASE_URL}/api/v1/payments/${BLOCKED_ID}/trail" \
  -H "Authorization: Bearer ${OPS_TOKEN}" | jq .
echo

echo "▶ Act 4 — Adaptive routing: MockPay degrades, the bandit shifts traffic to Stripe…"
curl -sf -X POST "${BASE_URL}/dev/providers/mockpay/program" \
  -H 'Content-Type: application/json' \
  -d '{"outcomes":["HARD_FAIL"]}' >/dev/null
# Let the bandit observe a few MockPay failures (each fails over to Stripe and completes).
for i in $(seq 1 5); do
  curl -sf "${BASE_URL}/api/v1/payments/charge" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H 'Content-Type: application/json' \
    -H "Idempotency-Key: demo-route-seed-${i}" \
    -H 'X-Correlation-Id: demo-corr-routing' \
    -d '{"amount_cents":2500,"currency":"USD","customer_email":"buyer@example.com"}' >/dev/null || true
done
# Thompson sampling is stochastic per charge, so demonstrate the *aggregate* shift over a batch.
STRIPE_FIRST=0
LAST_ROUTE_ID=""
for i in $(seq 1 12); do
  RESP=$(curl -sf "${BASE_URL}/api/v1/payments/charge" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H 'Content-Type: application/json' \
    -H "Idempotency-Key: demo-route-batch-${i}" \
    -H 'X-Correlation-Id: demo-corr-routing' \
    -d '{"amount_cents":2500,"currency":"USD","customer_email":"buyer@example.com"}') || continue
  LAST_ROUTE_ID=$(echo "${RESP}" | jq -r .payment_id)
  FIRST=$(curl -sf "${BASE_URL}/api/v1/payments/${LAST_ROUTE_ID}/trail" \
    -H "Authorization: Bearer ${OPS_TOKEN}" | jq -r '.routing.ordered_providers[0] // empty')
  [[ "${FIRST}" == "stripe" ]] && STRIPE_FIRST=$((STRIPE_FIRST + 1))
done
echo "   ${STRIPE_FIRST}/12 charges routed Stripe-first after MockPay degraded"
[[ "${STRIPE_FIRST}" -ge 8 ]] || {
  echo "Expected the bandit to route Stripe-first for most charges, got ${STRIPE_FIRST}/12" >&2
  exit 1
}
ROUTE_TRAIL=$(curl -sf "${BASE_URL}/api/v1/payments/${LAST_ROUTE_ID}/trail" \
  -H "Authorization: Bearer ${OPS_TOKEN}")
echo "${ROUTE_TRAIL}" | jq '.routing'
# Deterministic proof the bandit learned: MockPay's posterior mean sits below Stripe's.
MP_MEAN=$(echo "${ROUTE_TRAIL}" | jq -r '.routing.providers.mockpay.bandit.posterior_mean')
ST_MEAN=$(echo "${ROUTE_TRAIL}" | jq -r '.routing.providers.stripe.bandit.posterior_mean')
awk "BEGIN{exit !(${MP_MEAN} < ${ST_MEAN})}" || {
  echo "Expected MockPay posterior mean (${MP_MEAN}) < Stripe (${ST_MEAN})" >&2
  exit 1
}
echo "   Bandit posteriors — MockPay mean=${MP_MEAN} < Stripe mean=${ST_MEAN} (learned MockPay is degraded)"
echo "   Note: under the bandit the breaker rarely trips — the bandit demotes MockPay before its"
echo "         failure window fills. The deterministic breaker→OPEN proof lives in RoutingShiftIT."
echo "   Grafana → Provider Health: Stripe first-choice share ↑, MockPay posterior ↓"
echo

echo "▶ Act 5 — Recovery: MockPay healthy again, the bandit re-explores and re-learns…"
curl -sf -X POST "${BASE_URL}/dev/providers/mockpay/program" \
  -H 'Content-Type: application/json' \
  -d '{"outcomes":["AUTHORIZED"]}' >/dev/null
# Thompson sampling keeps probing the demoted provider; each success rebuilds its posterior.
for i in $(seq 1 15); do
  curl -sf "${BASE_URL}/api/v1/payments/charge" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H 'Content-Type: application/json' \
    -H "Idempotency-Key: demo-recovery-${i}" \
    -H 'X-Correlation-Id: demo-corr-recovery' \
    -d '{"amount_cents":2500,"currency":"USD","customer_email":"buyer@example.com"}' >/dev/null || true
done
RECOVERY=$(curl -sf "${BASE_URL}/api/v1/payments/charge" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-recovery-final' \
  -H 'X-Correlation-Id: demo-corr-recovery-final' \
  -d '{"amount_cents":2500,"currency":"USD","customer_email":"buyer@example.com"}')
RECOVERY_ID=$(echo "${RECOVERY}" | jq -r .payment_id)
echo "   Recovery trail (MockPay bandit posterior climbing as probes succeed):"
curl -sf "${BASE_URL}/api/v1/payments/${RECOVERY_ID}/trail" \
  -H "Authorization: Bearer ${OPS_TOKEN}" | jq '.routing.providers.mockpay.bandit, .routing.providers.stripe.bandit'
echo "   Explore/exploit: Thompson sampling keeps probing recovered MockPay instead of waiting"
echo "     for a fixed window to age out — its success count (alpha) climbs back over time."
echo

echo "▶ Where to look next"
echo "   Grafana dashboards:  http://localhost:3000  (Provider Health → routing + breaker panels)"
echo "   Tempo traces:        search sentinelpay.correlation_id=demo-corr-happy"
echo "   MailHog inbox:       http://localhost:8025"
echo
echo "✓ Demo complete — happy path, failover, risk block, adaptive routing shift, and bandit recovery observed."
