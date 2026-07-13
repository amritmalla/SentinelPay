# Observability

SentinelPay exposes the three pillars of observability for the charge correctness thesis: structured
logs, Prometheus metrics, and Tempo distributed traces — unified in Grafana for the portfolio demo.

## Signals

| Signal | Backend | Access |
|--------|---------|--------|
| **Logs** | JSON stdout (`logback-spring.xml`, LogstashEncoder) | `docker compose logs` or IDE console |
| **Metrics** | Prometheus (scrapes `/actuator/prometheus`) | Grafana dashboards, `:9090` |
| **Traces** | Tempo (OTLP HTTP `:4318`) | Grafana → Explore → Tempo |

`correlationId` (`X-Correlation-Id` / MDC `requestId`) is the business id. The OTel `traceId` is the
technical trace id. They are linked via span attribute `sentinelpay.correlation_id` (searchable in Tempo).

### Trace export (opt-in profile)

Each service includes `application-observability.yml` with OTLP HTTP export to Tempo. That file is
loaded **only** when the `observability` Spring profile is active — it is **not** in the default
`application.yml`, so services start without any `OTLP_TRACES_ENDPOINT` and without a running Tempo
instance.

| Mode | Spring profiles | OTLP exporter |
|------|-----------------|---------------|
| Default dev / tests | `dev` on gateway only (or none) | off — spans stay in-process |
| Full trace demo | `observability` on all services; gateway also needs `dev` | on → Tempo `:4318` |

Do **not** set `management.otlp.tracing.endpoint` to an empty value in default config: Spring Boot still
instantiates the OTLP exporter and fails startup with `Invalid endpoint, must start with http:// or https://`.

Override the endpoint with `OTLP_TRACES_ENDPOINT` (profile default: `http://localhost:4318/v1/traces`;
use `http://tempo:4318/v1/traces` inside Docker networks).

## Metric catalog

| Meter | Type | Service | Tags |
|-------|------|---------|------|
| `sentinelpay_charge_duration_seconds` | Timer | payment | — |
| `sentinelpay_recovered_authorization_total` | Counter | payment | `provider` |
| `sentinelpay_double_capture_total` | Counter | payment | — |
| `sentinelpay_provider_authorization_total` | Counter | payment | `provider`, `outcome` |
| `sentinelpay_provider_call_duration_seconds` | Timer | payment | `provider`, `op` |
| `sentinelpay_risk_fallback_total` | Counter | payment, risk | — |
| `sentinelpay_risk_decision_total` | Counter | risk | `recommendation` |
| `sentinelpay_outbox_pending` | Gauge | payment, risk, provider | `service` |
| `sentinelpay_outbox_oldest_pending_seconds` | Gauge | payment, risk, provider | `service` |
| `kafka_consumer_fetch_manager_records_lag` | (Kafka client) | notification | topic/client |
| `http_server.requests` | (auto RED) | all | uri, status |

HTTP RED is automatic via Spring Boot Actuator — do not duplicate it.

## SLOs (28-day)

| SLI | SLO | Source |
|-----|-----|--------|
| Charge availability | 99.9% non-5xx | `http_server_requests_seconds_count{uri=~".*charge"}` |
| Charge added latency p99 | < 150 ms | `sentinelpay_charge_duration_seconds` (excl. provider time in analysis) |
| Correctness | `double_capture_total == 0` | `sentinelpay_double_capture_total` |

## Alerts

| Alert | Severity | Runbook |
|-------|----------|---------|
| `ChargeAvailabilityBurnCritical` | page | [charge-availability-burn.md](operations/runbooks/charge-availability-burn.md) |
| `ChargeAvailabilityBurnTicket` | ticket | [charge-availability-burn.md](operations/runbooks/charge-availability-burn.md) |
| `ChargeLatencyBurnCritical` | page | [charge-latency-burn.md](operations/runbooks/charge-latency-burn.md) |
| `DoubleCaptureDetected` | critical (page) | [double-capture.md](operations/runbooks/double-capture.md) |

Rules live in `ops/observability/prometheus/rules.yaml`. `DoubleCaptureDetected` is intentionally a raw
threshold alert: the invariant is binary (0 or broken).

## Dashboards

Provisioned from `ops/observability/grafana/provisioning/dashboards/`:

1. **Charge Golden Signals** — rate, errors, p50/p95/p99, 150 ms budget gauge
2. **Correctness** — double-capture stat, recovered-auth rate, risk-fallback rate
3. **Provider Health** — per-provider success ratio and call latency
4. **Pipeline Health** — outbox depth, oldest-pending age, Kafka consumer lag

## See it in 2 minutes

1. Start infra and observability stack:

   ```bash
   docker compose up -d
   ```

2. Run services with trace export enabled (`observability` profile):

   ```bash
   ./mvnw -q -pl api-gateway spring-boot:run -Dspring-boot.run.profiles=dev,observability
   ./mvnw -q -pl payment-service spring-boot:run -Dspring-boot.run.profiles=observability
   ./mvnw -q -pl risk-service spring-boot:run -Dspring-boot.run.profiles=observability
   ./mvnw -q -pl provider-service spring-boot:run -Dspring-boot.run.profiles=observability
   ./mvnw -q -pl notification-service spring-boot:run -Dspring-boot.run.profiles=observability
   ```

   Optional: `export OTLP_TRACES_ENDPOINT=http://localhost:4318/v1/traces` to override the profile default.

3. Obtain a dev JWT (gateway needs `dev` profile) and fire a happy-path charge:

   ```bash
   MERCHANT_ID=$(uuidgen)
   TOKEN=$(curl -s http://localhost:8080/dev/token \
     -H 'Content-Type: application/json' \
     -d "{\"role\":\"MERCHANT\",\"merchant_id\":\"$MERCHANT_ID\"}" | jq -r .token)
   curl -s -X POST http://localhost:8080/api/v1/payments/charge \
     -H "Authorization: Bearer $TOKEN" \
     -H "Idempotency-Key: demo-happy-1" \
     -H "X-Correlation-Id: demo-corr-happy" \
     -H "Content-Type: application/json" \
     -d '{"amount_cents":2500,"currency":"USD","customer_email":"buyer@example.com"}'
   ```

4. Force failover (program MockPay to fail in test/dev) and charge again with a new idempotency key.
   Open Grafana at `http://localhost:3000` → **Correctness** dashboard: `double_capture_total` stays **0**,
   `recovered_authorization_total` increments.

5. In Grafana → Explore → Tempo, search `sentinelpay.correlation_id=demo-corr-happy` and confirm a single
   trace: Gateway → Payment (HTTP) → Risk (gRPC) → provider calls, plus a linked Kafka consumer span on
   `payment.completed`.

## CI enforcement

`FailoverChargeIT` asserts `sentinelpay_double_capture_total == 0` and
`sentinelpay_recovered_authorization_total >= 1` after the chaos failover run — the correctness thesis is
enforced as a metric, not only a DB invariant.
