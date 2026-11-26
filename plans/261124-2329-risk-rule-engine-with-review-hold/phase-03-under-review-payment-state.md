---
phase: 3
title: "Under review payment state"
status: completed
priority: P1
dependencies: [2]
effort: "1-2 sessions"
---

# Phase 3: Under review payment state

## Overview

Add the `REVIEW` action. A matching payment is held in a new `UNDER_REVIEW` state: no provider call, no pay URL, still "live" for the duplicate-order guard, and it ends by itself when the review deadline passes. Approval comes in phase 4. Learning note `12-review-hold-and-live-states.md`.

## Requirements
- Functional: REVIEW match -> `201` with `status = UNDER_REVIEW`, `payUrl = null`; merchant can cancel a held payment; sweeper expires it after `review-timeout`.
- Non-functional: every status change still goes through `PaymentStateMachine.transition`; no provider call and no HTTP inside a transaction.

## Failures reproduced first
1. Add `UNDER_REVIEW` to the enum only: create the same order twice while the first is held -> two live payments (index does not know the new state).
2. Leave the sweeper unchanged: a held payment stays `UNDER_REVIEW` forever.

## Architecture
```
create(): decision REVIEW
  tx1 { insert CREATED
        transition CREATED -> UNDER_REVIEW (reason = rule.reason_code, source SYSTEM)
        providerCode = resolved code; expiresAt = now + review-timeout
        record risk decision (REVIEW, payment id) }
  return payment            // no provider call
```
- Insert and hold happen in one transaction, so `failAbandonedCreated()` never sees the row as `CREATED`.
- `PaymentStateMachine.ALLOWED`: `CREATED` gains `UNDER_REVIEW`; `UNDER_REVIEW -> FAILED, EXPIRED, CANCELLED` (the approve edge is added in phase 4).
- `expireOverdue()` sweeps `PENDING` (reason unchanged) and `UNDER_REVIEW` (reason `REVIEW_TIMEOUT`); it already keys on `expires_at`.
- `cancel()` needs no change beyond the transition table.

## Related Code Files
- Create: `V11__add_under_review_to_live_order_index.sql` (drop and recreate `ux_payments_live_order` with `UNDER_REVIEW` in the predicate, one migration = one transaction)
- Create: `V12__allow_review_action_and_seed_rule.sql` (widen the `risk_rules.action` check to `REJECT, REVIEW`; seed global rule `amount gt 20000000 -> REVIEW HIGH_AMOUNT`, priority 200, so the 50M REJECT rule at 100 still wins)
- Modify: `payment/PaymentStatus.java`, `payment/PaymentStateMachine.java`, `payment/Payment.java` (setter for `expiresAt`), `payment/PaymentOrchestrator.java` (REVIEW branch, `expireOverdue`), `payment/PaymentRepository.java` if the overdue query needs a second status, `risk/RiskDecision.java` (`REVIEW` outcome), `application.yml` (`fluxpay.risk.review-timeout: 30m`)
- Modify tests: `payment/PaymentStateMachineTest.java` (new table rows)
- Create tests: `payment/ReviewHoldIT.java`
- Create: `docs/learning/12-review-hold-and-live-states.md`; Postman requests

## Implementation Steps
1. Add the enum value alone and reproduce failure 1 with a temporary test; then `V11` fixes it.
2. `V12`; extend `RiskDecision.Outcome` and the compiler/loader to accept `REVIEW`.
3. State machine table + `PaymentStateMachineTest` updates (allowed and forbidden edges, including `UNDER_REVIEW -> PENDING` and `UNDER_REVIEW -> SUCCEEDED` forbidden).
4. REVIEW branch in `create()`.
5. Reproduce failure 2 with `MutableClock`, then extend `expireOverdue()`.
6. `ReviewHoldIT`: held payment has no `providerRef`/`payUrl`, provider call count 0; second create same order -> 409 `ORDER_ALREADY_EXISTS`; merchant cancel -> `CANCELLED`; clock past `review-timeout` + sweep -> `EXPIRED` with `REVIEW_TIMEOUT`; `GET` shows the two-row ledger; same idempotency key replays the 201.
7. Check every `switch`/comparison on `PaymentStatus` in main and test code (webhook path, DTOs, `load-tests/verify-invariants.sql`) for the new value.
8. `./mvnw verify`; note 12: what "live" means, why the index and the state machine must change together, why the hold is one transaction.

## Success Criteria
- [x] Acceptance 6 passes
- [x] Held payment expires through the sweeper; cancel works
- [x] No webhook can move an `UNDER_REVIEW` payment (it has no `provider_ref`; test asserts lookup miss behaviour is unchanged)
- [x] `./mvnw verify` green; note 12 written

## Risk Assessment
- Contract change: clients that assume `payUrl` is non-null on 201. Documented in README in phase 4; Postman test updated here.
- `load-tests/verify-invariants.sql` may enumerate statuses; update or the invariant report misleads.
- Index rebuild takes a lock; irrelevant at this data size, mention in the note.
