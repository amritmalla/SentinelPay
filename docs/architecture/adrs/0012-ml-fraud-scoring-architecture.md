---
id: 0012
title: ML fraud scoring via Python sidecar (HTTP) with Java orchestration
status: accepted
date: 2026-07-17
supersedes: null
---

# ADR 0012: ML fraud scoring via Python sidecar (HTTP)

## Context

ADR-0007 left an ONNX-ready `RiskScorer` seam behind a rule-based v1. Phase 9 introduces a trained model. The risk-service already owns velocity (Redis), assessment persistence, outbox events, gRPC to payment-service, and BLOCK/REVIEW thresholds. Replacing that orchestrator with a Python gRPC server would orphan those concerns or force a reimplementation.

**Honesty:** the model is trained on *synthetic* data. The deliverable is the ML engineering lifecycle (data → features → train → evaluate → calibrate → serve → explain → drift), not real-world fraud-detection efficacy.

## Decision

1. **Java remains the orchestrator.** A new `MlScorer` implements `RiskScorer` and calls a Python FastAPI service (`risk-model`) over HTTP/JSON. `RuleBasedScorer` is the in-process Layer-1 fallback.
2. **Serving protocol is HTTP + JSON**, not gRPC. The payload is ~10 scalars; FastAPI + pydantic is the idiomatic ML serving skill for the portfolio. A gRPC port can be added later without changing the model core.
3. **Model is LightGBM**, isotonic-calibrated, with a logistic-regression baseline in the evaluation report. Thresholds (BLOCK ≥ 0.70, REVIEW ≥ 0.30) stay in Java config.
4. **Feature plumbing:** `merchant_category` and `card_country` are optional charge fields (closing OpenAPI drift). `merchant_country` is server-sourced from `sentinelpay.merchants.*` config — not client-attested. A real merchant profile store is out of scope.
5. **`model_version`** format `ml-v{semver}+{dataset-hash-8}` is baked into the artifact metadata and returned on every score.

## Consequences

**Benefits:** portfolio demonstrates a full ML lifecycle; charge-path resilience is preserved (two-layer fallback); Java CI stays hermetic via WireMock; train/serve skew is prevented by a shared feature module.

**Downsides:** an extra container and ~tens of ms latency on the critical path; synthetic-data metrics must be framed honestly; SHAP per request adds CPU cost (acceptable at demo volume).

## Alternatives considered

- **Python implements `RiskScoringService` gRPC directly.** Rejected: orphans Redis velocity, assessment persistence, and outbox.
- **ONNX in-process in Java.** Rejected for now: weaker portfolio story for Python ML tooling; HTTP sidecar is easier to iterate and explain.
- **Feature store / model registry service.** Rejected as out of scope; `models/metadata.json` in-repo is the registry.
