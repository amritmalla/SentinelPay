# Charge availability burn runbook

## Symptom

`ChargeAvailabilityBurnCritical` or `ChargeAvailabilityBurnTicket` fired — charge endpoint 5xx rate is burning the 99.9% availability SLO.

## Dashboard

Grafana → **Charge Golden Signals** → error rate panel.

## First checks

1. Confirm scope: is the burn global or limited to one merchant/provider?
2. Check payment-service logs for stack traces (`docker compose logs` or IDE console).
3. Inspect `http_server_requests_seconds_count{uri=~".*charge",status=~"5.."}` by status code.
4. Verify downstream dependencies: Postgres, Kafka, risk gRPC, provider adapters.

## Likely causes

- Database connectivity or pool exhaustion
- Risk service unavailable (fallback should still allow charges — investigate if fallback path also fails)
- Provider adapter misconfiguration
- Recent deployment regression

## Rollback

Revert the latest payment-service or gateway deployment. If infra-related, restart affected dependency containers.
