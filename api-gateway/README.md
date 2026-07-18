# api-gateway

The platform edge and **authentication boundary**: validates JWTs, injects identity and correlation
headers, rate-limits, and routes to downstream services (Spring Cloud Gateway, reactive).
See [backend-architecture.md](../docs/architecture/backend-architecture.md).

> **Scaffold status:** production shell only. JWT validation and the Redis token-bucket rate limiter
> are delivered by the downstream `spring-security-auth-review` skill; proxied routes are currently
> open at this stage (see Production Notes).

## Prerequisites

- Java 17
- Maven (or the repo `./mvnw` wrapper)
- Docker (for local infrastructure / running the downstream services)

## Run Locally

```bash
./mvnw -pl api-gateway spring-boot:run          # routes to payment/risk/provider on their default ports
```

## Run Tests

```bash
./mvnw -pl api-gateway test                     # reactive context-load test (no Docker required)
```

## Profiles

- `dev`: console logs, `com.sentinelpay` at DEBUG, full health detail, full trace sampling.
- `prod`: JSON logs, actuator credentials required (`MANAGEMENT_PASSWORD`), 10% trace sampling, health detail only when authorized.

## Routes

| Path | Downstream |
|---|---|
| `/api/v1/payments/**`, `/api/v1/ops/**`, `/api/v1/webhooks/**` | payment-service (`8082`) |
| `/api/v1/fraud-assessments/**` | risk-service (`8083`) |

provider-service (`8085`) has no REST surface — it participates over gRPC and Kafka, so the gateway does not route to it.

## Environment Variables

| Variable | Required | Description | Example |
|---|---:|---|---|
| `SERVER_PORT` | No | HTTP port | `8080` |
| `PAYMENT_SERVICE_URI` | No | Payment service base URI | `http://localhost:8082` |
| `RISK_SERVICE_URI` | No | Risk service base URI | `http://localhost:8083` |
| `PROVIDER_SERVICE_URI` | No | Provider service base URI | `http://localhost:8085` |
| `TRACE_SAMPLE` | No | Trace sampling probability | `1.0` |
| `MANAGEMENT_USER` | prod | Actuator basic-auth user | `admin` |
| `MANAGEMENT_PASSWORD` | prod | Actuator basic-auth password (no default; fail-fast if unset) | `<secret>` |

## Actuator

- Health: `/actuator/health` (+ `/liveness`, `/readiness`) — open for probes
- Info: `/actuator/info` — open
- Metrics: `/actuator/metrics`, Prometheus: `/actuator/prometheus` — require actuator credentials

## Task Commands

| Command | Description |
|---|---|
| `./mvnw -pl api-gateway spring-boot:run` | Run the gateway |
| `./mvnw -pl api-gateway test` | Run tests |
| `make run-gateway` | Run via the repo Makefile |

## Production Notes

- **Deferred to `spring-security-auth-review`:** JWT validation at this boundary and the Redis
  token-bucket rate limiter. Until then, proxied routes are permitted (the actuator is still secured).
  The gateway is the only internet-facing component; downstream services trust its injected headers.
- No database; no Testcontainers needed. The context-load test runs without Docker.
