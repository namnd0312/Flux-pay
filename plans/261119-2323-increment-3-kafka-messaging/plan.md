---
title: "Flux-pay Increment 3: Kafka Messaging (learning-first)"
description: "Increment 3 of the learning-first mini payment gateway: Kafka from zero, webhook ingestion through a transactional outbox and a Kafka consumer, at-least-once consumer safety with a dead-letter topic, outbound merchant notifications, then re-measure with JMeter."
status: pending
priority: P2
branch: "feat/kafka-messaging"
tags: [payment-gateway, learning, kafka, backend, database]
blockedBy: [261124-2329-risk-rule-engine-with-review-hold]
blocks: []
created: "2026-11-19T16:31:58.535Z"
createdBy: "ck:plan"
source: skill
---

# Flux-pay Increment 3: Kafka Messaging (learning-first)

## Overview

Increment 2 made the synchronous core correct under concurrency. Increment 3 moves work off the request thread through Kafka and teaches Kafka from zero. Same learning contract for every phase: **reproduce the failure -> concept note (`docs/learning/NN-*.md`, Vietnamese prose, English terms, numbers 14-18) -> fix -> verify (Testcontainers IT + compose/CLI lab) -> "Tự phá thử" exercise**. Claude writes the code; failure demos come first.

Scope decision: HOLD, roadmap as written (user, 2026-11-19). Sources: roadmap `../reports/brainstorm-261106-2236-mini-payment-gateway-learning-roadmap-report.md`, load-test note `../../docs/learning/09-load-test-and-bottlenecks.md`, research in `./research/`, red-team reports in `./reports/`.

### What is true today (read in code)
- `webhook_events` is already a durable ledger (`RECEIVED / RETRYABLE / APPLIED / REJECTED`); the HTTP thread does verify + apply + mark in 3-4 transactions and answers 503 to make the provider redeliver.
- Payment commit and ledger mark are separate transactions: a crash between them leaves a paid event that a redelivery labels `REJECTED ILLEGAL_TRANSITION`.
- Nothing tells the merchant a payment finished; the merchant polls.

### Key decisions
| Topic | Decision |
|---|---|
| Honest framing | On one instance Kafka is **not** needed for correctness: the ledger table plus a `SKIP LOCKED` worker would be simpler. Kafka is here to learn it and for fan-out. Note 15 says so plainly |
| Webhook answer | Ack-then-process: verify -> one tx {ledger `RECEIVED` + outbox row} -> 200. The 503 "retry later" path, `reclaimRetryable` and `EVENT_IN_PROGRESS` are removed; the body shrinks to `{received, duplicate}` (follows from the chosen scope) |
| Publish | Generic `outbox` table + one polling relay on its own thread (`FOR UPDATE SKIP LOCKED`, publish then mark = at-least-once). Direct `KafkaTemplate.send` after commit exists only as the phase 2 failure demo |
| Message shape | key = payment id, value = ledger row id (claim check), headers outbox id + publish generation. String serializers only. A record is untrusted input |
| Retry after ack | Consumer reschedules the outbox row (generation-checked) and the relay publishes again. Bounded: 12 attempts, then `REJECTED RETRY_EXHAUSTED` (user, 2026-11-24; replaces increment 2's "no cap", which relied on the provider's schedule) |
| Published vs processed | The relay re-arms rows published long ago whose ledger row is still not terminal |
| Time | Schedule times come from the injected `Clock`, passed into SQL; tests move `MutableClock` |
| Consumer safety | No processed-messages table: ledger row lock + payment row lock + transition + ledger mark in one transaction; the payment is never loaded before it is locked |
| Failures in the listener | Dead-letter topic only for records that can never succeed; anything else keeps the offset and retries with capped backoff |
| Listener threads | Concurrency = partition count, set explicitly (the default is one thread for all partitions) |
| Merchant notifications | On Kafka, second consumer group (user, 2026-11-24). Terminal transition flushes the payment update, then writes `merchant_notifications` + outbox row. Retry by outbox reschedule, `DEAD` after 6 attempts. Table worker documented as the better-isolating alternative |
| Callback signing | Derived key `HMAC(secret, "fluxpay-callback-v1")` (user, 2026-11-24); redirects off; receiver checks timestamp tolerance |
| Tests | Listeners and relay off in the default test context; tests call processors and the relay directly; a few dedicated listener ITs with unique groups |
| Packages | `messaging` (topics, outbox, relay, error handling) and `notification` (merchant callback). Fake merchant endpoint lives in `mock-provider` |
| Local files | `docker-compose.yml`, `.env.example`, `postman/` stay git-ignored; phase 1 snapshots the compose file for the "before" run |

### Out of scope
Asynchronous provider call on create, reconciliation, circuit breaker, per-merchant failure budget, rate limit, expiry-sweeper changes (increment 4), CDC/Debezium, Kafka transactions, schema registry, multi-broker, pool or thread tuning, a merchant self-service API for the callback URL, callback host/address allow-listing.

### Acceptance (whole increment)
- Broker stopped, or killed, during a webhook burst: every webhook still gets 200; afterwards the outbox drains and every event is `APPLIED` or `REJECTED`.
- Duplicate or concurrent delivery of one record: exactly one terminal transition; no applied event labelled `ILLEGAL_TRANSITION`.
- A poison record lands in the dead-letter topic and the next record on that partition is still processed; a passing fault dead-letters nothing.
- Cancel racing a webhook still answers 200 or 409, never 5xx.
- Every terminal payment has one notification row; a merchant that fails then recovers ends `DELIVERED` with a stable `webhook-id` and a signature checked independently of the signer.
- `./mvnw verify` green; notes 14-18 written; note 18 states predictions made before the run and whether they held.

### Known limits carried forward
- Merchants are isolated from each other only across partitions; no per-merchant failure budget; long retry windows (hours/days), replay of `DEAD` rows and key rotation are not built.
- `RETRY_EXHAUSTED` events and paid-but-`FAILED`/`EXPIRED` payments need reconciliation (increment 4).
- Outbox rows are never purged.
- Sync provider call still holds request threads when the provider is slow (note 09 §4.2).

## Phases

| Phase | Name | Status |
|-------|------|--------|
| 1 | [Kafka From Zero and Local Broker](./phase-01-kafka-from-zero-and-local-broker.md) | Pending |
| 2 | [Async Webhook Ingestion with Transactional Outbox](./phase-02-async-webhook-ingestion-with-transactional-outbox.md) | Pending |
| 3 | [At-Least-Once Consumer Idempotency and Dead Letter](./phase-03-at-least-once-consumer-idempotency-and-dead-letter.md) | Pending |
| 4 | [Outbound Merchant Notifications](./phase-04-outbound-merchant-notifications.md) | Pending |
| 5 | [Load Test Remeasure and Async Report](./phase-05-load-test-remeasure-and-async-report.md) | Pending |

## Dependencies

- Follows `261112-2102-increment-2-concurrency-hardening` (merged to `main` 2026-11-26) and `261124-2329-risk-rule-engine-with-review-hold`. Create `feat/kafka-messaging` from `main` once the rule engine is merged.
- The rule engine adds payment status `UNDER_REVIEW` (held before the provider call; leaves to `CREATED`, `FAILED`, `EXPIRED` or `CANCELLED`), changes `failAbandonedCreated()` to key on `updated_at`, and takes learning notes 10-13 and migrations `V9`-`V12`. This plan's notes are therefore 14-18 and its migrations `V13`-`V14`; re-read each phase against that code before starting it. Tag the starting commit `increment-2-done` and copy the compose file to `docker-compose.increment-2.yml` before editing it (phase 1).
- Phase order is strict: 1 -> 2 -> 3 -> 4 -> 5.
- Versions managed by Spring Boot 4.1.2 (read from its BOM): spring-kafka 4.1.2, kafka-clients 4.2.2, Testcontainers 2.0.5, Awaitility 4.3.0. Starters: `spring-boot-starter-kafka`, `spring-boot-starter-kafka-test`.
- Migrations: `V13` (outbox + backfill of non-terminal ledger rows), `V14` (merchant callback URL + `merchant_notifications`).
- Docker required (Testcontainers, compose). Library behaviour that research or review could not verify is a spike step in the phase that depends on it.

## Red Team Review

### Session — 2026-11-24
**Findings:** 15 after dedup of 24 (13 accepted, 2 accepted in part, 0 rejected). None was executed; those resting on Postgres/Hibernate/Kafka runtime behaviour carry a spike or a "reproduce first" step.
**Severity breakdown:** 1 Critical, 9 High, 5 Medium
**Reviewers:** Security Adversary, Failure Mode Analyst, Assumption Destroyer (reports in `./reports/`)

| # | Finding | Severity | Disposition | Applied To |
|---|---------|----------|-------------|------------|
| 1 | Notification insert with `unique(payment_id)` inverts lock order vs cancel: deadlock or 500 | Critical | Accept | Phase 3, 4 |
| 2 | Shared broker / database / cached contexts: listeners and leftovers cross tests | High | Accept | Phase 1, 2, 4 |
| 3 | "Published" treated as "processed"; transient faults dead-lettered; no backfill for old rows | High | Accept | Phase 2, 3 |
| 4 | Phase 2 failure demo would not lose events as written | High | Accept | Phase 2 |
| 5 | Retry schedule on database `now()`: untestable without minutes of waiting; cap test on an unreachable branch | High | Accept | Phase 2, 4, 5 |
| 6 | Phase 5 is 63 runs, not 27; "before" stack not rebuildable from the tag | High | Accept | Phase 1, 5 |
| 7 | Default listener concurrency 1 makes the partition lesson false | High | Accept in part | Phase 1, 3, 4, 5 |
| 8 | Callback URL SQL used the wrong api key; `NOTIFY=1` green on zero deliveries | High | Accept | Phase 4, 5 |
| 9 | Callback signature never checked independently; no timestamp tolerance at the receiver | High | Accept | Phase 4 |
| 10 | Redirects not disabled; inactive merchant still notified | High | Accept | Phase 4 |
| 11 | Reschedule not tied to a publish generation; `attempts` had two writers; header trusted | Medium | Accept | Phase 2, 4 |
| 12 | Pre-lock payment read leaves a stale managed entity in the one-transaction consumer | Medium | Accept | Phase 3 |
| 13 | Relay transaction pins a connection across broker waits and shared the scheduler thread | Medium | Accept in part | Phase 2 |
| 14 | Lists of affected tests and docs wrong or incomplete | Medium | Accept | Phase 2, 3, 4 |
| 15 | Free-text error columns and DLT headers leak or overflow | Medium | Accept | Phase 2, 3, 4 |

Rejected parts, with reason: per-merchant failure budget (#7) is the circuit-breaker work already assigned to increment 4, listed as a known limit; "claim, then send with no transaction open" (#13) adds a claim column and lease expiry for a single relay whose hold time is bounded by batch size and producer timeouts, kept as the documented alternative and measured in phase 5.

### Whole-Plan Consistency Sweep
- Files reread: plan.md, phase-01, phase-02, phase-03, phase-04, phase-05
- Decision deltas checked: 14 (flush before notification insert; listeners off in tests + unique groups; poison-only dead letter; re-drive; `Clock`-based schedule; process-death loss demo; generation-checked reschedule; id-only lookup before lock; relay thread; concurrency = partitions; derived callback key; redirects off; reason tokens; 36-run matrix from a worktree)
- Reconciled stale references: 12 (database `now()` in relay SQL; relay on the shared scheduler; 3 blocking attempts then dead-letter; "retried once" reschedule; relay failure counter sharing `attempts`; broker-pause / closed-port demo; 1-partition test option; "partition-level isolation" wording; `api_key = 'DEMO'`; API secret reused for callbacks; "27+ runs"; "needs sign-off" markers now decided)
- Unresolved contradictions: 0

## Validation Log

### Session 1 — 2026-11-24
Verification: covered by the red-team pass (fact checks, flow traces and caller lists in `./reports/`). Remaining unverified library behaviour is assigned to spikes: phase 1 A-E, phase 2 step 1-2, phase 3 steps 2 and 5, phase 4 steps 1 and 6.

| # | Question | Decision | Affects |
|---|----------|----------|---------|
| 1 | Apply the 15 red-team findings? | Yes, as adjudicated (2 in part) | All phases |
| 2 | Merchant notifications on Kafka or on a table worker? | Kafka, second consumer group | Phase 4 |
| 3 | Cap consumer-owned retries? | 12 attempts, then `REJECTED RETRY_EXHAUSTED` | Phase 2 |
| 4 | Callback signing key | Derived key, not the API secret | Phase 4 |

Open (not blocking): whether a callback for `FAILED` at create and for `CANCELLED` is wanted; the plan sends one for every terminal state.
