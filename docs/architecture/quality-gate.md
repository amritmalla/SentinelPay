# Quality Gate

SentinelPay enforces quality on every pull request through GitHub Actions. The gate is designed so that correctness, contract fidelity, and basic hygiene cannot regress silently.

## What runs on every PR

| Job | What it does |
|-----|----------------|
| **build** | `./mvnw -B verify` — full Maven lifecycle including unit tests (Surefire) and integration tests (Failsafe / Testcontainers) |
| **openapi-lint** | Redocly lint on `docs/architecture/contracts/openapi.yaml` |

### Correctness gate (the headline check)

The **build** job runs the full Testcontainers suite. This includes:

- **`FailoverChargeIT`** — proves **double-charge = 0** under provider failover (exactly one capture per payment).
- **`FailoverChargeIT.chaosRecoveredAuth_atLeast95PercentWithSingleCapture`** — chaos/recovered-auth path with a single capture invariant.

These integration tests are not optional extras; they are the platform's correctness proof for payment routing. CI must be green before merge.

### Contract gate

Controller tests in **payment-service** and **risk-service** validate successful HTTP responses against the published OpenAPI spec (`docs/architecture/contracts/openapi.yaml`) using Atlassian's `swagger-request-validator-mockmvc`. A response that drifts from the contract fails the build.

gRPC contracts remain compile-time enforced via generated stubs and `RiskScoringServerIT`.

### Coverage (report only)

JaCoCo runs on both unit and integration test phases. Reports are uploaded as CI artifacts (`jacoco`); there is **no hard coverage threshold** in v1.

## Reproduce locally

```bash
docker-compose up -d   # Postgres, Kafka, Redis for Testcontainers-backed ITs
./mvnw verify
```

This is the same command CI runs (batch mode `-B` is optional locally).

### OpenAPI lint locally

```bash
npx --yes @redocly/cli@latest lint docs/architecture/contracts/openapi.yaml
```

## Branch protection (manual GitHub step)

The workflow files live in-repo, but **making the gate binding** requires GitHub branch protection on `main`:

1. Repository **Settings → Branches → Branch protection rules** for `main`.
2. Enable **Require a pull request before merging**.
3. Enable **Require status checks to pass** and select:
   - `build`
   - `openapi-lint`
4. Save.

Until branch protection is configured, CI runs on PRs but merge is not mechanically blocked.

## Artifacts

On every CI run (even when tests fail), the **build** job uploads:

- **test-reports** — Surefire/Failsafe XML under `**/target/*-reports/`
- **jacoco** — HTML coverage under `**/target/site/jacoco/`

## Out of scope (later)

- Hard JaCoCo coverage thresholds
- Supply-chain scanning / SLSA provenance (see deployment hardening)
- Load/performance gates
