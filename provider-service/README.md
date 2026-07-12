# provider-service

Uniform provider abstraction with a common `PaymentProvider` interface: Stripe (test mode) and a
controllable MockPay adapter, authorize/capture, normalized error taxonomy, and rolling
provider-health monitoring. See [backend-architecture.md](../docs/architecture/backend-architecture.md)
and [ADR-0006](../docs/architecture/adrs/0006-provider-abstraction-mockpay.md).

> **Scaffold status:** production shell only. The provider adapters, health monitor, and Redis
> rolling windows are wired by the downstream feature-implementation skills.

## Prerequisites

- Java 17
- Maven (or the repo `./mvnw` wrapper)
- Docker (for Testcontainers tests and local infrastructure)
- PostgreSQL (provided by the repo `docker-compose.yml`)

## Run Locally

```bash
docker compose up -d postgres-provider         # from repo root
./mvnw -pl provider-service spring-boot:run
```

## Run Tests

```bash
./mvnw -pl provider-service test               # boots against a Testcontainers PostgreSQL
```

## Profiles

- `dev`: console logs, `com.sentinelpay` at DEBUG, full health detail, full trace sampling.
- `prod`: JSON logs, actuator credentials required (`MANAGEMENT_PASSWORD`), 10% trace sampling, health detail only when authorized.

## Environment Variables

| Variable | Required | Description | Example |
|---|---:|---|---|
| `SERVER_PORT` | No | HTTP port | `8085` |
| `PROVIDER_DB_URL` | No | JDBC URL | `jdbc:postgresql://localhost:5436/sentinelpay_provider` |
| `PROVIDER_DB_USER` | No | DB user | `sentinelpay` |
| `PROVIDER_DB_PASSWORD` | No | DB password | `sentinelpay_secret` |
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
| `./mvnw -pl provider-service spring-boot:run` | Run the service |
| `./mvnw -pl provider-service test` | Run tests |
| `make run-provider` | Run via the repo Makefile |

## Production Notes

- **Deferred to feature-implementation skills:** the Stripe/MockPay adapters, provider-health monitor,
  and Redis rolling windows. This shell keeps the Postgres + Flyway baseline and a passing
  Testcontainers context test.
- Application endpoints trust the API Gateway (auth boundary); this service is not internet exposed.
- Provider API keys are secrets (env/secret store), never stored in application tables.
- `V1__init.sql` is a no-op baseline; the real schema is authored by `postgres-schema-and-migration`.
