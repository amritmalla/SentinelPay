# risk-service

Staged, pluggable risk-evaluation pipeline producing explainable assessments (score + contributing
factors + recommendation). Serves the Payment Service over gRPC on the critical path.
See [backend-architecture.md](../docs/architecture/backend-architecture.md) and
[ADR-0007](../docs/architecture/adrs/0007-pluggable-risk-scorer-strategy.md).

> **Scaffold status:** production shell only. The scoring pipeline (`RiskScorer` Strategy), gRPC
> server, Redis velocity, and Kafka/outbox are wired by the downstream feature-implementation skills.

## Prerequisites

- Java 17
- Maven (or the repo `./mvnw` wrapper)
- Docker (for Testcontainers tests and local infrastructure)
- PostgreSQL (provided by the repo `docker-compose.yml`)

## Run Locally

```bash
docker compose up -d postgres-risk             # from repo root
./mvnw -pl risk-service spring-boot:run
```

## Run Tests

```bash
./mvnw -pl risk-service test                   # boots against a Testcontainers PostgreSQL
```

## Profiles

- `dev`: console logs, `com.sentinelpay` at DEBUG, full health detail, full trace sampling.
- `prod`: JSON logs, actuator credentials required (`MANAGEMENT_PASSWORD`), 10% trace sampling, health detail only when authorized.

## Environment Variables

| Variable | Required | Description | Example |
|---|---:|---|---|
| `SERVER_PORT` | No | HTTP port | `8083` |
| `RISK_DB_URL` | No | JDBC URL | `jdbc:postgresql://localhost:5435/sentinelpay_risk` |
| `RISK_DB_USER` | No | DB user | `sentinelpay` |
| `RISK_DB_PASSWORD` | No | DB password | `sentinelpay_secret` |
| `DB_POOL_MAX` | No | Hikari max pool size | `10` |
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
| `./mvnw -pl risk-service spring-boot:run` | Run the service |
| `./mvnw -pl risk-service test` | Run tests |
| `make run-risk` | Run via the repo Makefile |

## Production Notes

- **Deferred to feature-implementation skills:** the risk pipeline/scorer (`sentinelpay-proto` gRPC
  server), Redis velocity counters, and Kafka/outbox. This shell keeps the Postgres + Flyway baseline
  and a passing Testcontainers context test.
- Application endpoints trust the API Gateway (auth boundary); this service is not internet exposed.
- `V1__init.sql` is a no-op baseline; the real schema is authored by `postgres-schema-and-migration`.
