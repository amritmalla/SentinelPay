# SentinelPay Documentation

This tree is the authoritative documentation for SentinelPay, produced through the
idea → system-design → data-architecture → backend-architecture chain. Each artifact is versioned
and status-tracked in its own frontmatter.

## Map

```
docs/
├── product/
│   ├── vision/          Aspirational north-star: the Adaptive Payment Intelligence Platform
│   └── PRD.md            Approved v1 scope (the buildable wedge: resilient routing & failover)
└── architecture/
    ├── system-design.md         Bounded contexts, 5-service topology, failure modes
    ├── data-architecture.md     Ownership, consistency, indexing, retention
    ├── backend-architecture.md  Service contracts, domain model, flows, idempotency
    ├── observability.md         Metrics, traces (opt-in profile), SLOs, alerts, dashboards
    ├── adrs/                    Decision records 0001–0011 (accepted)
    └── contracts/
        ├── openapi.yaml          Public/ops REST API (OpenAPI 3.1, lint-clean)
        └── api-conventions.md    Error registry, idempotency, pagination, rate limits
```

## Reading order

1. [product/vision/](product/vision/) — what SentinelPay aspires to be (the platform identity).
2. [product/PRD.md](product/PRD.md) — the narrowed, approved v1.
3. [architecture/system-design.md](architecture/system-design.md) — the architectural envelope.
4. [architecture/data-architecture.md](architecture/data-architecture.md) and [backend-architecture.md](architecture/backend-architecture.md) — implementation direction.
5. [architecture/observability.md](architecture/observability.md) — signals, opt-in trace export, SLOs, and runbooks.
6. [architecture/adrs/](architecture/adrs/) — why each non-obvious decision was made.

## Status

| Artifact | Status |
|---|---|
| PRD | approved · v0.3.0 |
| system-design (+ ADRs 0001–0009) | approved · v0.2.0 |
| data-architecture (+ ADRs 0010–0011) | approved · v0.1.0 |
| backend-architecture (+ OpenAPI) | approved |
| v2 extension (ADRs 0012–0017) | delivered — ML scoring, adaptive routing, real Stripe, dashboard |

The architecture chain above was approved for **v1** and is kept at its approved version. Work
delivered after that (Phases 9–12) is recorded in ADRs 0012–0017 and summarised in the *v2 extension*
sections of [system-design.md](architecture/system-design.md#v2-extension-phases-9-12) and
[backend-architecture.md](architecture/backend-architecture.md#v2-extension-phases-9-12).

## History note

An earlier `docs/02-architecture/` (a 10-service target topology with an asynchronous decision
choreography) and a `docs/reference/` PayGuard project were removed on 2026-07-12 during a
documentation cleanup. They were superseded by the approved architecture above (a 5-service topology
with a **synchronous** critical path, per [ADR-0002](architecture/adrs/0002-synchronous-critical-path.md)).
ADR-0002's body — immutable once accepted — still refers to the former `docs/02-architecture/event-catalog.md`
as the choreography it overrode; that reference is historical and intentionally left as written.
