---
id: 0008
title: Transactional outbox for reliable event publication
status: accepted
date: 2026-07-12
supersedes: null
---

# ADR 0008: Transactional outbox for reliable event publication

## Context

Payment, Risk, and Provider each change durable state (e.g., a payment reaches `Completed`) and must publish a corresponding Kafka event consumed downstream (Notification, decision trail). Publishing directly to Kafka after the DB commit creates a dual-write problem: a crash between the DB commit and the Kafka send loses the event; publishing before commit can emit events for state that later rolls back.

## Decision

Adopt the **transactional outbox** pattern. Domain events are written to an `outbox` table **in the same DB transaction** as the state change. A per-service relay polls the outbox and publishes to Kafka **at-least-once**, marking rows published. Consumers are **idempotent**, deduping by `eventId`.

## Consequences

**Benefits:** no lost or phantom events; event emission is atomic with state change; consumers tolerate redelivery; a well-understood pattern that showcases reliable event-driven design.

**Downsides (required):**
- Adds an `outbox` table and a relay process per producer — real operational and code burden for a solo build.
- At-least-once means duplicate deliveries happen; every consumer must implement dedup (extra work, enforced by convention).
- Relay lag adds latency to fan-out (acceptable: fan-out is off the critical path).

**Revisit when:** adopting a CDC tool (e.g., Debezium) to replace the hand-rolled relay, or if a managed transactional-messaging feature becomes available.

## Alternatives considered

- **Direct publish after commit.** Rejected: dual-write race loses events on crash — unacceptable for an audit/notification trail.
- **Publish inside the DB transaction (before commit).** Rejected: emits events for rolled-back state.
- **Event sourcing.** Rejected: no replay/temporal requirement in v1 justifies the modeling cost (anti-pattern per the tradeoffs guide).
