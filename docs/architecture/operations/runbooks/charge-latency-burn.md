# Charge latency burn runbook

## Symptom

`ChargeLatencyBurnCritical` fired — charge path p99 exceeds the 150 ms budget.

## Dashboard

Grafana → **Charge Golden Signals** → duration percentiles and 150 ms gauge.

## First checks

1. Compare `sentinelpay_charge_duration_seconds` p99 vs `sentinelpay_provider_call_duration_seconds` — is provider time dominating?
2. Check risk gRPC latency in Tempo traces (Payment → Risk hop).
3. Review DB slow queries / connection pool metrics on payment-service.
4. Inspect rate limiting or thread pool saturation on gateway.

## Likely causes

- Risk service cold start or Redis velocity store latency
- Provider authorize/capture slowness (excluded from internal budget but extends user-visible time)
- Database lock contention on hot idempotency keys
- Insufficient sampling misread — confirm with longer window

## Rollback

Scale risk service or reduce charge traffic. Revert recent routing/provider changes if correlated with latency spike.
