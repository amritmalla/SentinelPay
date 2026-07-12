---
id: 0007
title: Pluggable risk scorer via Strategy interface (rule-based v1, ML-ready)
status: accepted
date: 2026-07-12
supersedes: null
---

# ADR 0007: Pluggable risk scorer via Strategy interface (rule-based v1, ML-ready)

## Context

The PRD promotes the risk check from a minimal gate into a first-class **risk-evaluation pipeline** to make "Intelligence First" architecturally real, while accepting an imperfect scorer for v1 (portfolio priority is the pipeline pattern, not accuracy). The design must let a future ML/ONNX model replace the scorer without reworking the pipeline, and must keep the pipeline explainable.

## Decision

Structure the Risk Service as a staged **orchestrator** that assembles features and delegates scoring to a `RiskScorer` **Strategy interface**. v1 ships a **rule-based scorer** (additive rules from the reference engine) returning `{score, contributingFactors[], recommendation}`. The orchestrator (feature assembly, velocity, caching, persistence, events, explainability) is independent of the scorer implementation, leaving an **ONNX-ready seam**. Some features may be stubbed/hardcoded in v1 (as in the reference engine's TODOs).

## Consequences

**Benefits:** the "evaluate every payment before execution" pattern is real and demonstrable; a future ML model is a scorer swap, not a redesign; every assessment is explainable (factors), satisfying the trail/metric; mirrors the reference and the target Risk Service.

**Downsides (required):**
- v1 scoring accuracy is low and partly synthetic (stubbed features); the pipeline looks more capable than the model is — acceptable and stated for a portfolio, but not production risk protection.
- The Strategy seam and feature-assembly abstraction add structure that a single hardcoded function would not need.
- Rule weights are static config, not learned; false-positive/negative behavior is untuned.

**Revisit when:** introducing a trained model (adds training/eval/versioning burden — a separate ai-native-engineering effort) or real signal sources (device/IP/behavioral).

## Alternatives considered

- **Inline hardcoded rule function (no Strategy).** Rejected: forecloses the ML swap and buries explainability; contradicts the PRD's pipeline intent.
- **Ship an ML model in v1.** Rejected (PRD non-goal): training data, evaluation harness, and drift monitoring are out of scope before the pipeline + failover spine is proven.
