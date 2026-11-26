---
title: "Flux-pay Increment 3a: Risk Rule Engine with Review Hold (learning-first)"
description: "Data-driven risk rule engine on payment creation (rules in Postgres, hand-written JSON condition evaluator, cached and audited), with REJECT and a REVIEW action that holds the payment in a new UNDER_REVIEW state until an operator approves or rejects it."
status: completed
priority: P2
branch: "feat/risk-rule-engine"
tags: [payment-gateway, learning, rule-engine, state-machine, backend, database]
blockedBy: []
blocks: [261119-2323-increment-3-kafka-messaging]
created: "2026-11-24T16:51:30.738Z"
createdBy: "ck:plan"
source: skill
---

# Flux-pay Increment 3a: Risk Rule Engine with Review Hold (learning-first)

## Overview

Goal is to learn the rule-engine pattern, not to meet a business need (1 provider, 1 demo merchant). Carrier feature: risk pre-check at the top of `PaymentOrchestrator.create()`. Same learning contract as earlier increments: **reproduce the failure -> concept note (`docs/learning/NN-*.md`, Vietnamese prose, English terms) -> fix -> self-verify in Postman**, each note ending with a "break it yourself" exercise.

Source: `../reports/brainstorm-261124-2329-risk-rule-engine-with-review-hold-report.md`. Runs **before** Kafka (user, 2026-11-24).

### Prerequisites (before phase 1)
1. Done 2026-11-26: `feat/concurrency-hardening` merged into `main` and pushed. Branch `feat/risk-rule-engine` from `main`. All file references below are to that code (migrations end at `V8`, notes end at `09`).
2. Done 2026-11-26: Kafka plan (`../261119-2323-increment-3-kafka-messaging/`) renumbered to notes 14-18 and migrations `V13`-`V14`, with a line that `UNDER_REVIEW` now exists.

### What is true today (read in code)
- `PaymentOrchestrator.create()`: resolve provider -> tx1 insert `CREATED` -> provider HTTP call with no tx -> tx2 `PENDING`. Updates go through `OptimisticRetry`.
- `PaymentStateMachine.ALLOWED`: `CREATED -> PENDING|FAILED`; `PENDING -> SUCCEEDED|FAILED|EXPIRED|CANCELLED`.
- `ux_payments_live_order`: unique `(merchant_id, order_id)` where status in `CREATED, PENDING, SUCCEEDED`.
- Sweeper: `expireOverdue()` (PENDING past `expires_at`), `failAbandonedCreated()` (CREATED with `created_at` older than `created-timeout: 2m`).
- `IdempotencyFilter.STORABLE_STATUSES` contains 201 and 422: a risk rejection and a held-payment response are both stored and replayed for the same key.
- `MerchantAuthFilter` is registered on `/api/*` only. Stores that need raw SQL use `JdbcClient` (`IdempotencyStore`).
- Existing tests and JMeter plans use `amount = 1000000`.

### Key decisions
| Topic | Decision |
|---|---|
| Engine | Rules are rows in `risk_rules`; hand-written evaluator; no library, no expression language |
| Condition grammar | JSON tree: `{"all":[...]}` / `{"any":[...]}` nodes, leaves `{"field","op","value"}`; ops `eq ne gt gte lt lte in`; fields whitelisted by the fact model |
| Facts | `amount, provider, merchantId, orderId, hourOfDay` (zone from config). No stateful facts (velocity is out) |
| Conflict resolution | Order by `priority` ascending then `id`; first match wins; no match = `ALLOW` |
| Scope of a rule | `merchant_id` null = global; a merchant rule wins only through a lower `priority` number |
| Bad rule | Compiled at load; a rule that fails to compile is skipped and logged at ERROR. This is fail-open for that rule, stated in note 10 |
| Cache | In-memory compiled list, swapped atomically, refreshed by `@Scheduled`; a failed refresh keeps the last good list. Startup load failure stops the app |
| Changing rules | SQL only (`psql`); no CRUD API |
| Audit | One `risk_decisions` row per evaluation, written in the same transaction as the payment insert (ALLOW/REVIEW) or alone (REJECT) |
| REJECT | `422 RISK_REJECTED`, no payment row, no provider call |
| REVIEW | Payment inserted and moved to `UNDER_REVIEW` in one transaction, no provider call, `201` with `payUrl = null` |
| Review deadline | `expires_at` = now + `fluxpay.risk.review-timeout` (30m) while held; reset to now + `fluxpay.payment.expiry` on approve |
| Approve | Claim transaction `UNDER_REVIEW -> CREATED` (reason `REVIEW_APPROVED`, source `OPERATOR`), then the existing provider-call tail to `PENDING`. Rules are not re-evaluated |
| Sweeper | `failAbandonedCreated()` keys on `updated_at` so a just-approved payment is not killed; `expireOverdue()` also covers `UNDER_REVIEW` |
| Operator auth | `/internal/*` guarded by `X-Admin-Token`, constant-time compare with `FLUXPAY_ADMIN_TOKEN`; app refuses to start if unset. No operator identities |
| Packages | `risk` (engine, no dependency on `payment`); review endpoints live in `payment`; admin filter in `common` |

### Out of scope
Velocity or any stateful rule, rule CRUD API/UI, rule version history, routing rules, fee rules, operator accounts/roles, pushing the review outcome to the merchant (arrives with Kafka notifications), re-evaluating rules at approve time.

### Acceptance (whole increment)
1. Seed rule `amount > 50,000,000 -> REJECT AMOUNT_OVER_LIMIT`: POST returns 422, 0 payment rows, 0 provider calls.
2. `UPDATE` of a rule threshold takes effect within one refresh interval, no restart.
3. A merchant-scoped rule beats a global rule through priority.
4. Every evaluation writes exactly one `risk_decisions` row with matched rule and facts snapshot.
5. Malformed rule JSON never produces a 500.
6. REVIEW match: payment is `UNDER_REVIEW`, no provider call; a second create for the same order returns 409.
7. Approve: exactly one provider call, payment `PENDING` with fresh `expires_at`; N concurrent approves still make one provider call.
8. Approve racing merchant cancel or review timeout: exactly one outcome, ledger consistent, no 5xx.
9. `/internal/*` rejects a missing or wrong admin token; merchant HMAC headers do not grant access.
10. `./mvnw verify` green including all increment 1 and 2 tests; notes 10-13 written.

### Public contract changes
- `POST /api/v1/payments` may answer `422 RISK_REJECTED`.
- `POST /api/v1/payments` may answer `201` with `status = UNDER_REVIEW` and `payUrl = null`.
- New status value `UNDER_REVIEW` in `GET /api/v1/payments/{id}`.

### Known limits
- A skipped malformed rule fails open; no alerting.
- Idempotent replay returns the stored 422 or the stored `UNDER_REVIEW` body even after rules change or the review ends; the merchant must poll `GET`.
- `risk_decisions` grows by one row per create; no retention job.

## Phases

| Phase | Name | Status |
|-------|------|--------|
| 1 | [Rule evaluator and reject at create](./phase-01-rule-evaluator-and-reject-at-create.md) | Done 2026-11-26 |
| 2 | [Rule cache refresh and decision audit](./phase-02-rule-cache-refresh-and-decision-audit.md) | Done 2026-11-26 |
| 3 | [Under review payment state](./phase-03-under-review-payment-state.md) | Done 2026-11-26 |
| 4 | [Operator approve reject and races](./phase-04-operator-approve-reject-and-races.md) | Done 2026-11-26 |

## Dependencies

- Needs increment 2 (`../261112-2102-increment-2-concurrency-hardening/`) merged into `main`.
- Blocks `../261119-2323-increment-3-kafka-messaging/`: that plan must be renumbered (notes 14-18) and re-read against `UNDER_REVIEW` and the sweeper change before it starts. Its terminal-transition notification hook covers `UNDER_REVIEW -> FAILED|EXPIRED|CANCELLED` without change.

## Resolved questions

- `review-timeout` default stays 30m (user, 2026-11-26).
- A malformed rule is skipped, fail-open for that rule (user, 2026-11-26).
