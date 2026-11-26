# Brainstorm Report: Risk Rule Engine with Review Hold

- Date: 2026-11-24
- Mode: brainstorm (no --html, no --wiki)
- Status: design approved by user
- Repo: `/Users/admin/Desktop/DEV/AI/Flux-pay`

## 1. Problem statement

User asked: can Flux-pay have a rule engine, and which feature would use it. Goal stated in discovery: learn the rule-engine pattern (not a business need). Feature picked to carry it: risk pre-check on payment creation.

## 2. Problem-first check

- **Solution-jumping signal:** "build a rule engine" arrived before any feature needed one.
- **Underlying problem:** no hands-on model of how data-driven decision logic works (fact model, condition evaluation, conflict resolution, audit) and what it costs when a decision holds a payment.
- **Evidence status:** none for a business need (1 provider, 1 demo merchant, `merchants` has no limits/fees). Strong as a learning goal (stated explicitly). Accepted on that basis.
- **Assumptions challenged:**
  - "Engine needs a library" -> rejected; Drools is heavy and Boot 4 compatibility unverified, SpEL-from-DB is an injection path.
  - "Everything can be a rule" -> state transitions, HMAC auth, idempotency, webhook dedup stay hard-coded invariants.
  - "REVIEW is just another action" -> false; a hold is a second state-machine feature (see section 5).
- **Kill criteria:** none applied; learning project.

## 3. Scout findings

- Java 21, Spring Boot 4.1, modular monolith `gateway/`, Postgres + Flyway. No rule-shaped code today.
- Hook point: top of `PaymentOrchestrator.create()`, after provider resolve, before tx1.
- `feat/concurrency-hardening` (increment 2) is unpushed, holds migration `V8` and learning notes 06-09.
- `PaymentStateMachine`: `CREATED -> PENDING|FAILED`, `PENDING -> SUCCEEDED|FAILED|EXPIRED|CANCELLED`.
- `ux_payments_live_order`: unique `(merchant_id, order_id)` where status in `CREATED, PENDING, SUCCEEDED`.
- Sweeper fails `CREATED` rows older than `created-timeout: 2m` by `created_at`; `expires_at` = creation + 15m.
- `MerchantAuthFilter` covers `/api/*` only, merchant HMAC.
- Kafka plan (increment 3) reserves learning notes 10-14; not started.

## 4. Decisions (captured via AskUserQuestion)

| Topic | Decision | Alternatives rejected |
|---|---|---|
| Feature | Risk pre-check before payment creation | Provider routing (needs 2nd provider), fee/MDR (no fee concept yet) |
| Engine style | Rules as data in DB, hand-written evaluator, no new dependency | Rules in code first; Drools / Easy Rules / SpEL |
| Timing | Now, before Kafka (user chose against recommendation "after increment 3") | After increment 3, after increment 4 |
| Actions | `REJECT` and `REVIEW` | `REJECT` only |
| REVIEW meaning | Hold payment for operator approval, new state `UNDER_REVIEW` (user chose against recommendation "flag-and-allow") | Flag-and-allow; separate table holding the raw request |
| Velocity rule | Out of this increment (dropped because hold-for-review was chosen) | Keep always |
| Branch base | Merge increment 2 into `main` first, then branch `feat/risk-rule-engine` | Stack on `feat/concurrency-hardening` |
| Learning notes | Rule engine notes come first, Kafka notes shift after | Keep Kafka at 10-14 |

Note numbering consequence: design now has 4 phases, so rule engine takes notes 10-13 and Kafka shifts to 14-18 (user approved the principle with 3 phases / 13-17).

## 5. Final design

New package `com.fluxpay.gateway.risk`.

### Schema
- `V9 risk_rules`: `id, name, merchant_id (null = global), priority, condition JSONB, action (REJECT|REVIEW), reason_code, enabled, updated_at`.
- `V10 risk_decisions` (append-only): `id, merchant_id, order_id, payment_id (null on REJECT), decision (ALLOW|REVIEW|REJECT), matched_rule_id, facts JSONB, created_at`.
- `V11`: recreate `ux_payments_live_order` with `UNDER_REVIEW` in the live set.

### Condition grammar
Tree of `all` / `any` with leaves `field / op / value`. Operators `eq ne gt gte lt lte in`. Fields whitelisted by the fact model: `amount, provider, merchantId, orderId, hourOfDay`. No expression language.

```json
{"all":[{"field":"amount","op":"gt","value":50000000}]}
```

### Evaluation
Rules ordered by `priority`, first match wins, no match = `ALLOW`. Malformed rule is skipped and logged at load. Rules cached in memory, refreshed by `@Scheduled`; edited via SQL, no admin CRUD API.

### Create flow
- `REJECT` -> `422 RISK_REJECTED` with the rule's `reason_code`; no payment row, no provider call.
- `REVIEW` -> payment inserted, moved to `UNDER_REVIEW`, no provider call; `201` with `status = UNDER_REVIEW`, `payUrl = null`. Merchant polls `GET /api/v1/payments/{id}`.
- `ALLOW` -> existing flow unchanged.

### Review hold
- `PaymentStatus.UNDER_REVIEW`, `TransitionSource.OPERATOR`.
- Transitions added: `CREATED -> UNDER_REVIEW`; `UNDER_REVIEW -> FAILED (RISK_REVIEW_REJECTED) | EXPIRED (REVIEW_TIMEOUT) | CANCELLED (MERCHANT_CANCEL)`; plus the approve path.
- Approve must claim the row in a transaction before the provider HTTP call (existing rule: no tx open during provider call), then reuse the create tail to reach `PENDING`. `expires_at` reset on approve.
- Operator API: `GET /internal/reviews`, `POST /internal/reviews/{paymentId}/approve|reject`, header admin token compared in constant time against an env var; app refuses to start if unset (same pattern as `FLUXPAY_SECRET_KEY`).
- Sweeper: expire `UNDER_REVIEW` past `review-timeout`; abandoned-`CREATED` check must not kill a just-approved payment.

### Phases (failure first)

| # | Scope | Failure reproduced first | Note |
|---|---|---|---|
| 1 | Fact model, evaluator, `risk_rules`, `REJECT` at create | Hard-coded `if amount >` limit needs a rebuild to change | 10 |
| 2 | Cache + refresh, `risk_decisions` audit | Cannot answer "why was this blocked"; DB read per request | 11 |
| 3 | `UNDER_REVIEW` state, index, create branch, review timeout | Duplicate order created while first is held; held payment never ends | 12 |
| 4 | Operator approve/reject, admin token, races | Double approve -> 2 provider calls; approve vs cancel; approve vs timeout; approved payment swept as abandoned | 13 |

## 6. Acceptance criteria

1. Seed rule `amount > 50,000,000 -> REJECT AMOUNT_OVER_LIMIT`: POST returns 422, 0 payment rows, 0 provider calls.
2. `UPDATE` of a rule threshold takes effect within one refresh interval, no restart.
3. Merchant-scoped rule beats a global rule through priority.
4. Every evaluation writes one `risk_decisions` row with matched rule and facts snapshot.
5. Malformed rule JSON never produces a 500.
6. `REVIEW` match: payment is `UNDER_REVIEW`, no provider call, second create for same order returns 409.
7. Approve -> exactly one provider call, payment `PENDING` with fresh `expires_at`; N concurrent approves -> still one provider call.
8. Approve racing merchant cancel or review timeout -> exactly one terminal outcome, audit ledger consistent.
9. Internal endpoints reject missing/wrong admin token; merchant HMAC does not grant access.
10. All existing increment 1 and 2 tests still green.

## 7. Out of scope

Velocity/stateful rules, admin CRUD API or UI for rules, routing rules, fee rules, rule version history, operator identities/roles, push notification of review outcome (arrives with Kafka).

## 8. Risks

- Increment 3 (Kafka) plan was validated 2026-11-24 and now waits behind a 4-phase increment; Kafka plan needs note renumbering and a re-check against the new status before it starts.
- Review hold changes a public contract: `POST /api/v1/payments` can return `201` without `payUrl`.
- `IdempotencyFilter` replays the stored response: a 422 stays 422 for the same key after a rule is relaxed, and a replayed `UNDER_REVIEW` body goes stale. Believed correct; verify in code during planning.
- Kafka plan's webhook consumer and merchant-notification logic were written without `UNDER_REVIEW`; a webhook cannot arrive for a held payment (no provider ref yet), but confirm.
- `gh` auth returned 401 on 2026-11-19; merging increment 2 needs `gh auth login`.

## 9. Success metrics

- Each phase runnable and verifiable in Postman in one session.
- User can explain: fact model, why a whitelist instead of an expression language, first-match vs collect-all, why approve needs a claim step.
- All 10 acceptance criteria covered by tests on Testcontainers.

## 10. Next steps

1. Push + PR + merge `feat/concurrency-hardening` into `main`.
2. `/ck:plan` with this report; branch `feat/risk-rule-engine` from `main`.
3. Renumber Kafka plan notes to 14-18 and re-check it against `UNDER_REVIEW`.

## Unresolved questions

- Approve claim mechanism: `UNDER_REVIEW -> CREATED` (reuses create tail, sweeper must switch to `updated_at`) vs a dedicated intermediate state.
- `review-timeout` default (30m assumed).
- Should a `REVIEW`-matched payment be re-evaluated against rules at approve time (assumed no).
- Default behaviour when the rule cache fails to load at startup: start with zero rules (fail-open) or refuse to start (fail-closed).
