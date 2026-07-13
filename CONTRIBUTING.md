# Contributing to SentinelPay

Thank you for your interest in contributing. This document outlines how to get started and what we expect from contributions.

## Getting Started

1. Fork the repository and clone your fork.
2. Start local infrastructure: `docker-compose up -d`
3. Build the project: `./mvnw clean install`
4. Run the full quality gate: `./mvnw verify`

## Development Workflow

1. Create a feature branch from `main`.
2. Make focused changes with clear commit messages.
3. Add or update tests when behavior changes.
4. Update documentation in `docs/` when APIs, architecture, or product scope change.
5. Open a pull request against `main`.

## Commit Messages

Use [Conventional Commits](https://www.conventionalcommits.org/):

```
feat(payment): add refund idempotency key
fix(fraud): correct velocity window TTL
docs(api): document webhook payload schema
```

## Pull Request Guidelines

- One approval required before merge.
- **CI must pass** — both the `build` job (`./mvnw verify`, including Testcontainers correctness tests) and `openapi-lint` must be green. See [Quality Gate](docs/architecture/quality-gate.md).
- Enable branch protection on `main` to require these checks (documented in the quality gate guide).
- Prefer squash merge for a clean history.
- Keep PRs focused — one concern per PR when possible.
- Link related issues or ADRs when applicable.

## Code Standards

- Match existing patterns in the module you are editing.
- Java 17, Spring Boot 3.2 conventions.
- No secrets in source control — use environment variables or local config.
- Run `./mvnw verify` before submitting.

## Documentation

Product and technical docs live under `docs/`. When your change affects:

| Area | Update |
|------|--------|
| Product / requirements | `docs/product/` (PRD, vision) |
| Architecture | `docs/architecture/` (system, data, backend) |
| Design decisions | `docs/architecture/adrs/` |
| API contracts | `docs/architecture/contracts/` |

For significant design decisions, add an ADR in `docs/architecture/adrs/` before or with your PR.

## Questions

Open an issue for bugs, feature requests, or design discussions before large changes.
