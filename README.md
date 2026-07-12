# SentinelPay

**Adaptive Payment Intelligence Platform** — an intelligence/decision layer between merchants and
payment providers. Rather than forwarding a charge to a single provider, SentinelPay evaluates every
payment and chooses the safest, most reliable way to execute it.

> **v1 scope (this build):** the platform's first capability — **resilient multi-provider routing &
> failover**: risk-evaluate → decide → route to a healthy provider → automatically fail over to a
> fallback on failure, with **guaranteed no double-charge** and a fully **explainable decision trail**.
> The broader platform vision is the north star, sequenced after v1. See
> [docs/product/vision/](docs/product/vision/) and [docs/product/PRD.md](docs/product/PRD.md).

## Documentation

Start at [docs/README.md](docs/README.md). The approved chain:

| Doc | Purpose |
|---|---|
| [PRD](docs/product/PRD.md) | Approved v1 scope, non-goals, success metrics |
| [System Design](docs/architecture/system-design.md) | Bounded contexts, topology, failure modes, ADRs |
| [Data Architecture](docs/architecture/data-architecture.md) | Ownership, consistency, indexing, retention |
| [Backend Architecture](docs/architecture/backend-architecture.md) | Service contracts, domain model, flows |
| [OpenAPI](docs/architecture/contracts/openapi.yaml) | Public/ops REST contract (3.1, lint-clean) |
| [ADRs](docs/architecture/adrs/) | Decision records 0001–0011 |

## Architecture at a glance

```
Merchant ──▶ API Gateway ──▶ Payment Service ──gRPC──▶ Risk Service
                                    │  (decision + routing + failover, idempotent)
                                    └──REST──▶ Provider Service ──▶ Stripe (test) / MockPay
                                    │
                             outbox ──▶ Kafka ──▶ Notification Service
```

Five services, a **synchronous** critical path (gRPC + REST) with **asynchronous** Kafka fan-out,
database-per-service (PostgreSQL), and Redis for ephemeral counters. Rationale in the
[ADRs](docs/architecture/adrs/).

## Modules

```
sentinelpay-parent (pom.xml)
├── sentinelpay-common       Shared web baseline (error envelope, correlation propagation)
├── sentinelpay-proto        gRPC/protobuf contract (risk scoring)
├── api-gateway              Edge: JWT auth, routing, rate limiting
├── payment-service          Lifecycle, decisioning, routing, failover, idempotency (system of record)
├── risk-service             Pluggable risk-evaluation pipeline (gRPC)
├── provider-service         Provider abstraction (Stripe + MockPay) + health
└── notification-service     Event-driven notifications
```

## Tech Stack

Java 17 · Spring Boot 3.2 · Spring Cloud Gateway · PostgreSQL 15 · Redis 7 · Apache Kafka · gRPC ·
Flyway · Micrometer/OpenTelemetry · Testcontainers · Maven (multi-module)

## Build

Requires **JDK 17** and **Maven 3.9+** (Docker for Testcontainers / local infra).

```bash
mvn -q -DskipTests verify     # compile & package all modules
mvn -q verify                 # + tests (needs a running Docker daemon)
docker compose up -d          # local infrastructure (Postgres, Redis, Kafka, MailHog)
```

The [Makefile](Makefile) provides task shortcuts (`make build`, `make test`, `make up`, `make run-payment`, …).

## Status

- **Architecture:** complete and approved (PRD → system-design → data-architecture → backend-architecture).
- **Implementation:** service scaffolding in progress. `sentinelpay-common` and `sentinelpay-proto`
  build green; service shells are being generated. Domain logic (charge/failover), Kafka/outbox,
  Redis, and gRPC runtime wiring are delivered by the downstream feature-implementation skills.

## Contributing & License

See [CONTRIBUTING.md](CONTRIBUTING.md). Licensed under [MIT](LICENSE).
