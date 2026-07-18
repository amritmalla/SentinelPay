---
id: 0015
title: Adaptive routing via Thompson sampling bandit
status: accepted
date: 2026-07-17
supersedes: null
---

# ADR 0015: Adaptive routing via Thompson sampling bandit

## Context

A static scored policy (`wS·successRate + wL·latencyScore`) ranks providers from health windows but cannot **explore** a recovered provider until its window ages out. Phase 10 needs online adaptation without a training pipeline, model artifacts, or Python sidecar — portfolio-honest ML that runs on the charge path.

## Decision

1. **Model: per-provider Beta-Bernoulli Thompson sampling** over authorization success. Posteriors `(α, β)` live in Redis next to health windows; updated on every attempt outcome.
2. **Rank by sampled `θ ~ Beta(α, β)` minus a small cost penalty** (`costWeight · normalizedFee`) so cheaper providers win at equal success odds.
3. **Decayed posteriors** (`bandit.decay-half-life-minutes`, default 30) pull stale beliefs back toward the prior so old outages are forgotten.
4. **Deterministic RNG seeded from `payment_id`** so idempotent replays produce identical ranks. Traffic split uses the same hash-bucket approach.
5. **Policy selection is config:** `routing.policy: static | scored | bandit`. CI correctness suites run `static`; demo/compose runs `bandit`. Scored remains the default for new deployments.
6. **No offline-trained routing model in this phase.** A logistic-regression success predictor is noted as the natural next step once labeled data exists (Phase 12 dashboard).

## Consequences

**Benefits:** online explore/exploit; trail records `(α, β)`, posterior mean, and sampled θ — explainable by hand; no new service; plugs into the same `RankingPolicy` seam as scored/static.

**Downsides:** stochastic first choice (mitigated by seeded replay for idempotency); Redis posteriors share the fail-open rule; convergence is slower than a tuned static formula for stable workloads.

## Alternatives considered

- **Contextual bandit / offline LR model.** Deferred: needs labeled data and training pipeline; plain bandit meets the phase exit criterion.
- **Epsilon-greedy on scored rank.** Rejected: weaker explainability story than Beta posteriors in the trail.
- **ONNX in-process success predictor.** Rejected for this phase: duplicates Phase 9 sidecar pattern without portfolio payoff on routing.
