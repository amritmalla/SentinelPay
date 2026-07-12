# notification-service

Consumes business events (`payment.completed`, `payment.failed`, `fraud.alert.high`, …) and delivers
notifications (email), idempotent per `eventId`. See
[backend-architecture.md](../docs/architecture/backend-architecture.md).

> **Scaffold status:** production shell only. The Kafka consumers, template/rendering, and mail
> delivery (MailHog) are wired by the downstream feature-implementation skills.

## Prerequisites

- Java 17
- Maven (or the repo `./mvnw` wrapper)
- Docker (for Testcontainers tests and local infrastructure)
- PostgreSQL (provided by the repo `docker-compose.yml`)

## Run Locally

```bash
docker compose up -d postgres-notifications    # from repo root
./mvnw -pl notification-service spring-boot:run
```

## Run Tests

```bash
./mvnw -pl notification-service test           # boots against a Testcontainers PostgreSQL
```

## Profiles

- `dev`: console logs, `com.sentinelpay` at DEBUG, full health detail, full trace sampling.
- `prod`: JSON logs, actuator credentials required (`MANAGEMENT_PASSWORD`), 10% trace sampling, health detail only when authorized.

## Environment Variables

| Variable | Required | Description | Example |
|---|---:|---|---|
| `SERVER_PORT` | No | HTTP port | `8084` |
| `NOTIFICATION_DB_URL` | No | JDBC URL | `jdbc:postgresql://localhost:5437/sentinelpay_notifications` |
| `NOTIFICATION_DB_USER` | No | DB user | `sentinelpay` |
| `NOTIFICATION_DB_PASSWORD` | No | DB password | `sentinelpay_secret` |
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
| `./mvnw -pl notification-service spring-boot:run` | Run the service |
| `./mvnw -pl notification-service test` | Run tests |
| `make run-notification` | Run via the repo Makefile |

## Production Notes

- **Deferred to feature-implementation skills:** the Kafka consumers, notification templates, and mail
  delivery (MailHog/SMTP). This shell keeps the Postgres + Flyway baseline and a passing Testcontainers
  context test.
- `V1__init.sql` is a no-op baseline; the real schema is authored by `postgres-schema-and-migration`.
