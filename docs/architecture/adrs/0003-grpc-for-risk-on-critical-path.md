---
id: 0003
title: gRPC for Risk evaluation on the critical path
status: accepted
date: 2026-07-12
supersedes: null
---

# ADR 0003: gRPC for Risk evaluation on the critical path

## Context

Given a synchronous critical path ([ADR-0002](0002-synchronous-critical-path.md)), the Payment Service must call the Risk Service on every charge to obtain a risk assessment before routing. The transport for this internal, latency-sensitive, high-frequency call must be chosen. The reference project already defines a protobuf fraud-scoring contract and calls it over gRPC.

## Decision

Use **gRPC** for the Payment → Risk `ScoreTransaction` call, defined by a shared `.proto` contract. Keep a thin REST debug endpoint on Risk for manual testing/admin. Provider calls remain REST (simpler, adapter-shaped, lower call frequency).

## Consequences

**Benefits:** typed, versioned contract enforced at build time; low serialization overhead and latency headroom under the PRD's 150 ms budget; demonstrates polyglot-ready internal RPC; reuses the reference proto.

**Downsides (required):**
- Adds protobuf/codegen tooling and a shared contract module to build and version.
- gRPC is less trivially debuggable than REST (needs grpcurl/reflection); partially offset by the REST debug endpoint.
- Two internal transports (gRPC + REST) is slightly more surface than one.

**Revisit when:** if Risk moves off the synchronous path (async assessment), or if the team wants a single internal transport for simplicity.

## Alternatives considered

- **REST/JSON for Payment→Risk.** Rejected: loses the typed contract and the reference proto; higher overhead for the hottest internal call. (Still used for Provider, where adapter semantics and lower frequency make REST the better fit.)
- **In-process call (merge Risk into Payment).** Rejected: would erase the gRPC boundary that is a core portfolio demonstration, and couples risk-rule changes to payment deploys.
