---
title: 'Flux-pay Increment 2: Concurrency Hardening (learning-first)'
description: >-
  Increment 2 of the learning-first mini payment gateway: handle optimistic-lock
  conflicts, make early webhooks recoverable, add a pessimistic-lock arm, fence
  the idempotency lease, then measure everything with JMeter.
status: completed
priority: P2
branch: main
tags:
  - payment-gateway
  - learning
  - concurrency
  - backend
  - database
blockedBy: []
blocks: []
created: '2026-11-12T16:19:16.818Z'
createdBy: 'ck:plan'
source: skill
---

# Flux-pay Increment 2: Concurrency Hardening (learning-first)

## Overview

Increment 1 built the synchronous core. Increment 2 makes it correct under concurrency and measures it. Same learning contract as increment 1, for every phase: **reproduce the failure -> concept note (`docs/learning/NN-*.md`, Vietnamese prose, English terms) -> fix -> verify (Testcontainers IT + Postman) -> "Tự phá thử" exercise**. Claude writes the code; failure demos come first.

Scope decision: HOLD (approved 2026-11-12). Sources: roadmap `../reports/brainstorm-261106-2236-mini-payment-gateway-learning-roadmap-report.md`, research in `./research/`.

### What the code review found (read, not yet reproduced)
- `@Version` is already active on `Payment`; nobody handles the conflict -> 500, and a webhook event stuck in `RECEIVED`.
- A webhook arriving before create tx2 commits is rejected with 200 and deduplicated forever -> paid order never SUCCEEDED.
- `IdempotencyStore.complete()/delete()` have no owner check -> a zombie worker overwrites or deletes the new owner's key.

### Key decisions
| Topic | Decision |
|---|---|
| Conflict handling | Hand-written bounded retry (3 attempts, fresh tx, re-read); no new dependency |
| Early webhook | Retryable outcome: 503 + event row `RETRYABLE`, reclaimed by conditional UPDATE on redelivery (same bytes); fallback decides from one row read by payment id; in-flight duplicate gets 503; mock-provider retries on 5xx/429/IO. No state-machine change |
| Locking on webhook path | `SELECT ... FOR UPDATE` behind `fluxpay.webhook.lock-mode` (default pessimistic) so phase 4 can compare both |
| Lease fence | `lease_epoch` column fed by a Postgres sequence (outlives row deletes), checked in the WHERE of complete/abandon |
| Local files | `docker-compose.yml`, `.env.example`, `postman/` stay git-ignored (user, 2026-11-12); phase 1 snapshots the compose file for the "before" run |
| Load tool | JMeter under `load-tests/`; "before" numbers from tag `increment-1-done` |

### Out of scope
Kafka / inbox consumer (increment 3), reconciliation and retry/circuit breaker on provider calls (increment 4), pool or thread tuning, refunds, deadlock and isolation-level labs.

### Acceptance (whole increment)
- No 5xx and no stuck webhook event under webhook-vs-cancel races (IT + JMeter), in both lock modes.
- Early webhook ends SUCCEEDED after provider redelivery.
- Zombie idempotency writes affect 0 rows, including after delete-then-re-claim.
- `./mvnw verify` green; notes 06-09 written with before/after numbers; bottleneck named with evidence.

### Known limits carried forward
- Event left `RECEIVED` by a JVM crash or DB failure, or `RETRYABLE` with no redelivery -> increment 3 (inbox) / 4 (reconciliation).
- Paid but FAILED (create timed out or was abandoned after the provider accepted; FAILED-then-SUCCESS) -> event kept as `REJECTED` with the payment linked; reconciliation in increment 4.
- Fenced replay may be `409 ORDER_ALREADY_EXISTS` while the zombie's 201 is not replayable (phase 3 risk).

## Phases

| Phase | Name | Status |
|-------|------|--------|
| 1 | [Optimistic Lock Conflicts and Retry](./phase-01-optimistic-lock-conflicts-and-retry.md) | Completed |
| 2 | [Early and Out-of-Order Webhooks with Pessimistic Lock](./phase-02-early-and-out-of-order-webhooks-with-pessimistic-lock.md) | Completed |
| 3 | [Idempotency Lease Fencing](./phase-03-idempotency-lease-fencing.md) | Completed |
| 4 | [JMeter Load Test and Bottleneck Report](./phase-04-jmeter-load-test-and-bottleneck-report.md) | Completed |

## Dependencies

- Follows `261106-2236-mini-payment-gateway-increment-1-core-lifecycle` (done, merged to `main`). No unfinished plans overlap.
- Phase order: 1 -> 2 (reuses retry helper); 3 independent; 4 needs 1-3.
- Work on branch `feat/concurrency-hardening`. Docker required (Testcontainers, compose). JMeter installed (`/opt/homebrew/bin/jmeter`).
- New migration: `V8__add_idempotency_lease_epoch.sql` only (sequence + column).

## Red Team Review

### Session — 2026-11-12
**Findings:** 15 after dedup (15 accepted, 2 of them in part; 0 rejected)
**Severity breakdown:** 2 Critical, 3 High, 10 Medium
**Reviewers:** Security Adversary, Assumption Destroyer, Failure Mode Analyst (reports in `./reports/`)

| # | Finding | Severity | Disposition | Applied To |
|---|---------|----------|-------------|------------|
| 1 | Early-webhook fallback used two unsynchronised reads | Critical | Accept | Phase 2 |
| 2 | In-row `lease_epoch` restarts at 1 after delete (ABA) | Critical | Accept | Phase 3 |
| 3 | Duplicate of in-flight `RECEIVED` event acked 200 | High | Accept | Phase 2 |
| 4 | Mock retry on a 2-4 thread pool; retries on 4xx | High | Accept | Phase 2, 4 |
| 5 | Compose / env / Postman git-ignored: "before" run and clean clone | High | Accept (keep local + recipe) | Phase 1, 4 |
| 6 | Metrics "bound to localhost" had no mechanism | Medium | Accept | Phase 4 |
| 7 | Reclaim: no byte equality, stale columns, unguarded updates | Medium | Accept in part (no attempt cap) | Phase 2 |
| 8 | `SET LOCAL lock_timeout` takes no bind parameter | Medium | Accept | Phase 2 |
| 9 | Planned ITs not buildable with shared fixtures | Medium | Accept | Phase 1, 2, 3 |
| 10 | JMeter race scenario not buildable; no host `psql` | Medium | Accept | Phase 4 |
| 11 | Invariant SQL vacuous / blind to money loss | Medium | Accept | Phase 4 |
| 12 | Paid-but-FAILED path mis-described, payment link lost | Medium | Accept | Phase 2, plan |
| 13 | 503 + `Retry-After` on create; webhook exhaustion status ambiguous | Medium | Accept in part (no new create compensation) | Phase 1, 2 |
| 14 | Missed consumers (`SessionControllerTest`, "always 200" docs/Javadoc) | Medium | Accept | Phase 2, 4 |
| 15 | "62 unit tests" count unverified | Medium | Accept | Phase 1 |

Rejected parts, with reason: retry-attempt cap on reclaim (only a holder of the provider secret can redeliver; provider schedule bounds it); compensation for create tx2 exhaustion (reviewers confirmed every `Payment` mutator: the retry bound holds, path unreachable).

### Whole-Plan Consistency Sweep
- Files reread: plan.md, phase-01, phase-02, phase-03, phase-04
- Decision deltas checked: 9 (sequence token; single-row fallback; 503 for in-flight duplicate; reclaim needs same bytes; mock retry policy and pool; lock-timeout mechanism; local-only compose + snapshot; management port; race scenario + invariants)
- Reconciled stale references: 11 (in-row epoch counter, `SET LOCAL` with bind parameter, two-read fallback, 200 for `RECEIVED` duplicate, mock retry defaults, `Retry-After` on create, "clean clone", age-filtered invariants, parallel-sampler scenario, host `psql`, "62 unit" count)
- Unresolved contradictions: 0

## Validation Log

### Session 1 — 2026-11-12
Verification: covered by the red-team pass (fact checks + caller lists in `./reports/`); no `[UNVERIFIED]` claim resolved here, all remaining ones are assigned to a spike step in their phase.

| # | Question | Decision | Affects |
|---|----------|----------|---------|
| 1 | Zombie create request refused by the fence: what does its client get? | Its own computed response; nothing stored; WARN log | Phase 3 |
| 2 | Stored replay may be `409 ORDER_ALREADY_EXISTS` after a zombie race | Accept and document in note 08; no change to the 409 body | Phase 3 |
| 3 | Webhook lock mode | Config toggle, default `pessimistic`; keep/remove decided after phase 4 data. Decided 2026-11-15: optimistic arm removed | Phase 2, 4 |
| 4 | Git-ignored compose / env / Postman | Keep local; snapshot + explicit "before" recipe | Phase 1, 4 |

### Whole-Plan Consistency Sweep
- Files reread: plan.md, phase-01, phase-02, phase-03, phase-04
- Decision deltas checked: 4 (all confirm the written defaults; one wording change in phase 3 risk)
- Reconciled stale references: 1
- Unresolved contradictions: 0
