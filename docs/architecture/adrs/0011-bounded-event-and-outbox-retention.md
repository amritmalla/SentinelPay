---
id: 0011
title: Bounded retention for outbox tables and Kafka topics
status: accepted
date: 2026-07-12
supersedes: null
---

# ADR 0011: Bounded retention for outbox tables and Kafka topics

## Context

The transactional outbox ([ADR-0008](0008-transactional-outbox.md)) writes an event row per state change, and every event is published to Kafka. Left unmanaged, both the `*_outbox` tables and the Kafka topics grow **without bound** — a well-known data-architecture anti-pattern (unbounded event/outbox storage) that eventually degrades relay-poll performance and consumes disk. A retention decision is needed because these stores are append-heavy by design.

## Decision

Bound both event stores:
- **Outbox tables:** a scheduled purge deletes rows that are `published_at IS NOT NULL` and older than a **7-day safety window** (long enough to debug/replay a recent failure, short enough to keep the unpublished-row partial index tiny).
- **Kafka topics:** **time-based retention of 7 days** (not compacted — these are lifecycle facts, not a keyed latest-state log). Downstream durable records (notifications, decision trail) already persist in Postgres, so Kafka is a transport, not the system of record.

The financial system of record (`payment`, `payment_attempt`, `risk_assessment`) is unaffected — it retains indefinitely in v1.

## Consequences

**Benefits:** predictable, bounded storage for both event stores; the outbox relay's unpublished-row index stays small and fast; no dependency on Kafka as long-term storage; recent history still available for debugging/replay.

**Downsides (required):**
- Events older than 7 days cannot be replayed from Kafka; any future consumer needing full history must be backfilled from the Postgres systems of record instead.
- The purge job is another scheduled operational task that must run reliably (its own failure re-introduces unbounded growth).
- The 7-day window is a heuristic, not derived from a measured replay requirement; may need tuning once real consumers exist.

**Revisit when:** a consumer requires longer replay windows, event sourcing is adopted, or analytics needs a full historical event stream (then introduce an archival sink rather than extending Kafka retention).

## Alternatives considered

- **Infinite retention (no purge).** Rejected: the anti-pattern this ADR exists to prevent — unbounded disk growth and a slowing outbox poll.
- **Log-compacted Kafka topics.** Rejected: these are past-tense lifecycle events, not keyed current-state; compaction would drop intermediate transitions the trail cares about.
- **Delete outbox rows immediately on publish.** Rejected: leaves no short window to diagnose or replay a just-failed delivery.
