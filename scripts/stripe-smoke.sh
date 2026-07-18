#!/usr/bin/env bash
# Stripe test-mode smoke — validates API connectivity and documents webhook forwarding.
# Prereq: copy .env.example → .env with sk_test_… keys (never commit .env).
set -euo pipefail

if [[ -f .env ]]; then
  # shellcheck disable=SC1091
  source .env
fi

: "${STRIPE_API_KEY:?Set STRIPE_API_KEY in .env (sk_test_… only)}"

# Hard guard: this script creates, captures and refunds a real charge. Against a live-mode
# key that moves real money. Refuse anything that is not an explicit test-mode key, and
# never echo the key itself (only its mode).
case "${STRIPE_API_KEY}" in
  sk_test_*) ;;
  sk_live_*)
    echo "REFUSING TO RUN: STRIPE_API_KEY is a LIVE key (sk_live_…) — this script would move real money." >&2
    echo "Use a test-mode key (sk_test_…). If this key was ever committed or shared, rotate it now." >&2
    exit 1
    ;;
  *)
    echo "REFUSING TO RUN: STRIPE_API_KEY has an unrecognized prefix; expected sk_test_…" >&2
    exit 1
    ;;
esac

echo "▶ Stripe test-mode smoke (key mode: test)"
echo

echo "▶ 1. Create PaymentIntent (manual capture, test payment method)…"
# allow_redirects=never mirrors StripeProvider: without it, accounts with dashboard-enabled
# payment methods reject confirm=true unless a return_url is supplied.
PI=$(curl -sf https://api.stripe.com/v1/payment_intents \
  -u "${STRIPE_API_KEY}:" \
  -d amount=2500 \
  -d currency=usd \
  -d capture_method=manual \
  -d "automatic_payment_methods[enabled]=true" \
  -d "automatic_payment_methods[allow_redirects]=never" \
  -d payment_method="${STRIPE_PAYMENT_METHOD:-pm_card_visa}" \
  -d confirm=true)
PI_ID=$(echo "${PI}" | jq -r .id)
PI_STATUS=$(echo "${PI}" | jq -r .status)
echo "   intent=${PI_ID} status=${PI_STATUS}"
[[ "${PI_STATUS}" == "requires_capture" || "${PI_STATUS}" == "succeeded" ]] || {
  echo "Unexpected PaymentIntent status: ${PI_STATUS}" >&2
  echo "${PI}" | jq . >&2
  exit 1
}

echo "▶ 2. Capture…"
CAP=$(curl -sf "https://api.stripe.com/v1/payment_intents/${PI_ID}/capture" \
  -u "${STRIPE_API_KEY}:" \
  -X POST)
echo "   capture status=$(echo "${CAP}" | jq -r .status)"

echo "▶ 3. Refund…"
REF=$(curl -sf https://api.stripe.com/v1/refunds \
  -u "${STRIPE_API_KEY}:" \
  -d "payment_intent=${PI_ID}" \
  -d amount=2500)
echo "   refund id=$(echo "${REF}" | jq -r .id)"

echo
echo "▶ Optional: run SentinelPay against real Stripe"
echo "   docker compose -f docker-compose.yml -f docker-compose.stripe.yml up --build payment-service"
echo "   Forward webhooks: stripe listen --forward-to localhost:8080/api/v1/webhooks/stripe"
echo "   Set STRIPE_WEBHOOK_SECRET from the CLI signing secret in .env"
echo
echo "✓ Stripe test-mode smoke complete."
