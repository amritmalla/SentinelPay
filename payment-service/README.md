# payment-service

Critical-path orchestrator and system of record for the payment lifecycle: risk gate, provider
selection, in-request failover, idempotent exactly-once capture, refunds, and lifecycle events.
See [backend-architecture.md](../docs/architecture/backend-architecture.md).

> **Scaffold status:** production shell only. Domain logic (charge/failover), the gRPC risk client,
> Kafka/outbox, and Redis are wired by the downstream feature-implementation skills.

## Prerequisites

- Java 17
- Maven (or the repo `./mvnw` wrapper)
- Docker (for Testcontainers tests and local infrastructure)
- PostgreSQL (provided by the repo `docker-compose.yml`)

## Run Locally

```bash
docker compose up -d postgres-payments        # from repo root
./mvnw -pl payment-service spring-boot:run
```

## Run Tests

```bash
./mvnw -pl payment-service test                # boots against a Testcontainers PostgreSQL
```

## Profiles

- `dev`: console (human-readable) logs, `com.sentinelpay` at DEBUG, full health detail, full trace sampling.
- `prod`: structured JSON logs, actuator credentials required (`MANAGEMENT_PASSWORD`), 10% trace sampling, health detail only when authorized.

(No `staging` profile — no staging environment is defined for this portfolio build.)

## Environment Variables

| Variable | Required | Description | Example |
|---|---:|---|---|
| `SERVER_PORT` | No | HTTP port | `8082` |
| `PAYMENT_DB_URL` | No | JDBC URL | `jdbc:postgresql://localhost:5434/sentinelpay_payments` |
| `PAYMENT_DB_USER` | No | DB user | `sentinelpay` |
| `PAYMENT_DB_PASSWORD` | No | DB password | `sentinelpay_secret` |
| `DB_POOL_MAX` | No | Hikari max pool size | `10` |
| `TRACE_SAMPLE` | No | Trace sampling probability | `1.0` |
| `MANAGEMENT_USER` | prod | Actuator basic-auth user | `admin` |
| `MANAGEMENT_PASSWORD` | prod | Actuator basic-auth password (no default; fail-fast if unset) | `<secret>` |

## Actuator

- Health: `/actuator/health` (+ `/actuator/health/liveness`, `/actuator/health/readiness`) — open for probes
- Info: `/actuator/info` — open
- Metrics: `/actuator/metrics`, Prometheus: `/actuator/prometheus` — require actuator credentials

## Task Commands

| Command | Description |
|---|---|
| `./mvnw -pl payment-service spring-boot:run` | Run the service |
| `./mvnw -pl payment-service test` | Run tests |
| `make run-payment` | Run via the repo Makefile |

## Production Notes

- **Deferred to feature-implementation skills:** the payment domain/state machine, gRPC risk client
  (`sentinelpay-proto`), Redis, and Kafka/outbox wiring. This shell keeps the Postgres + Flyway
  baseline and a passing Testcontainers context test.
- Application endpoints are open at the service level by design — the **API Gateway is the auth
  boundary** (validates JWT, injects identity headers); this service is not internet exposed.
- `V1__init.sql` is a no-op baseline; the real schema is authored by `postgres-schema-and-migration`.
- Container image build is defined in `Dockerfile` (multi-stage, non-root) but not built as part of the scaffold.
