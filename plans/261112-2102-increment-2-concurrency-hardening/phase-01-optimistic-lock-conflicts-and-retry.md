---
phase: 1
title: Optimistic Lock Conflicts and Retry
status: completed
priority: P1
dependencies: []
effort: 1 day
---

# Phase 1: Optimistic Lock Conflicts and Retry

## Context Links
- Research: `research/researcher-01-locking-and-retry-report.md`
- Code: `gateway/src/main/java/com/fluxpay/gateway/payment/{Payment,PaymentOrchestrator,PaymentStateMachine,PaymentRepository}.java`, `webhook/WebhookIngestionService.java`, `common/ProblemDetailsHandler.java`
- Prior note: `docs/learning/01-state-machine.md` (mentions `@Version` reserved for this increment)

## Overview
`Payment.version` already carries `@Version`, so Hibernate already rejects a stale write, but no code handles the rejection. Two writers racing on one PENDING payment (webhook vs cancel, webhook vs sweeper) make the loser fail with an unhandled exception: HTTP 500, and on the webhook path the event row stays `RECEIVED` forever. This phase reproduces that, teaches lost update / optimistic locking, and adds a bounded retry so the loser re-reads and gets the correct business answer.

<!-- Updated: Red Team 2026-11-12 - PENDING start state for demo A, exhaustion contract, shared test helpers, compose snapshot -->

## Key Insights
- Exception to catch: Spring `OptimisticLockingFailureException` (`ObjectOptimisticLockingFailureException` is the JPA subclass). Surfaces at flush or commit of the `TransactionTemplate.execute` block; the audit-row insert rolls back with it. Verified on Boot 4.1.2 by `OptimisticLockConflictIT`: the writer's code completes, the exception is raised at the commit-time flush as `ObjectOptimisticLockingFailureException`.
- Every transition block already re-reads the entity inside its own `tx.execute` lambda (create tx2, `compensateToFailed`, `cancel`, `sweepOne`, webhook tx B). So a retry = run the same lambda again in a fresh transaction. No persistence-context reuse.
- Bound: `version` changes only together with a status transition, and a payment has at most 2 successful transitions in its life (CREATED -> PENDING -> terminal). A writer can therefore lose at most 2 times (in practice once); **3 attempts always suffice**. Red team re-verified every `Payment` mutator. After losing, the re-read sees the new status and the state machine answers (`IllegalPaymentTransitionException`) instead of the lock.
- Never retry `IllegalPaymentTransitionException`: it is a business answer, not a conflict.
- Hand-written loop, no new dependency. `@Retryable` is wrong here (programmatic transactions, self-invocation).

## Requirements
- Functional:
  - Concurrent webhook + cancel on one PENDING payment: exactly one wins; loser gets its normal business outcome (cancel -> 409 `PAYMENT_ILLEGAL_TRANSITION`; webhook -> 200 with `rejectReason=ILLEGAL_TRANSITION`). No 500.
  - Webhook event row never left in `RECEIVED` because of a lock conflict.
  - Retry exhaustion (unreachable by the bound above; defensive only): merchant API -> `503 CONCURRENT_UPDATE`, **no `Retry-After`**. 503 is not in `IdempotencyFilter.STORABLE_STATUSES`, so the key is released; but if it ever happened in create tx2 the committed CREATED row would block the same order (`ux_payments_live_order`) until the sweeper fails it after `created-timeout` (2m). Documented, no new compensation added.
  - Webhook path exhaustion: until phase 2 the global handler answers 503 and the event stays `RECEIVED`; phase 2 turns it into `RETRYABLE CONCURRENT_UPDATE` + 503.
- Non-functional: retry count observable via Micrometer counter `fluxpay.payment.optimistic.retries` (tag `path` = `create|cancel|webhook|compensate`). Sweeper does not retry (next sweep re-evaluates; existing per-row catch stays).

## Architecture
```
payment/OptimisticRetry (new, @Component, ~40 lines)
  <T> T execute(String path, TransactionTemplate tx, Supplier<T> work)
    for attempt in 1..3:
      try   -> return tx.execute(s -> work.get())      // fresh tx, lambda re-reads the row
      catch OptimisticLockingFailureException -> counter.increment(path); if attempt == 3 rethrow
```
Call sites (wrap the existing lambda, change nothing inside it):
| Site | File | Today |
|---|---|---|
| create tx2 | `PaymentOrchestrator.create` | `tx.execute` |
| compensate | `PaymentOrchestrator.compensateToFailed` | `tx.execute` |
| cancel | `PaymentOrchestrator.cancel` | `tx.execute` |
| webhook tx B | `WebhookIngestionService.validateAndApply` | `tx.execute` inside try/catch of `IllegalPaymentTransitionException` |

Not wrapped: tx1 insert (no version conflict possible), `sweepOne` (REQUIRES_NEW, per-row catch).

`ProblemDetailsHandler`: add `@ExceptionHandler(OptimisticLockingFailureException.class)` -> 503 `CONCURRENT_UPDATE` (plain `ProblemDetail`, same shape as the other handlers).

## Related Code Files
- Create: `gateway/src/main/java/com/fluxpay/gateway/payment/OptimisticRetry.java`
- Create: `gateway/src/test/java/com/fluxpay/gateway/payment/OptimisticLockConflictIT.java` (deterministic mechanism test)
- Create: `gateway/src/test/java/com/fluxpay/gateway/payment/WebhookVsCancelRaceIT.java` (real-path race, invariants)
- Create: `gateway/src/test/java/com/fluxpay/gateway/payment/OptimisticRetryTest.java` (unit: retries 2 then succeeds; rethrows on 3rd; does not retry other exceptions)
- Create: `docs/learning/06-optimistic-locking.md`
- Modify: `PaymentOrchestrator.java`, `WebhookIngestionService.java`, `common/ProblemDetailsHandler.java`
- Modify (tests): `AbstractIntegrationTest.java` (move the private webhook signing helper out of `WebhookIngestionIT.java:86-93` so new ITs share it), `WebhookIngestionIT.java` (use the shared helper)
- Postman: none for this phase (as built). Postman cannot send two requests at the same instant; `WebhookVsCancelRaceIT` is the demo.

## Implementation Steps
1. Create branch `feat/concurrency-hardening`; tag current `main` as `increment-1-done` (phase 4 measures "before" against it). `docker-compose.yml` is git-ignored, so also snapshot it now, before phase 2 edits it: `cp docker-compose.yml docker-compose.increment-1.yml` (local file; add the name to `.gitignore`).
2. **Failure demo A (mechanism, deterministic):** `OptimisticLockConflictIT`. Two threads, each with a test-owned `TransactionTemplate` (not `@Transactional`). Start from a **PENDING** payment (version 1; CREATED -> CANCELLED is not a legal move). T1 loads it and calls `stateMachine.transition(.., CANCELLED)`, then waits on a latch before commit. T2 loads, transitions to SUCCEEDED, commits. Release T1 -> assert `OptimisticLockingFailureException`, final status SUCCEEDED, exactly 1 terminal audit row. Record the exact exception class and whether it fired at flush or commit; fix the [UNVERIFIED] note.
3. **Failure demo B (real paths):** `WebhookVsCancelRaceIT`. For 200 PENDING payments: two threads on a `CyclicBarrier`, one POSTs a signed SUCCESS webhook, one POSTs cancel (MockMvc, `AbstractIntegrationTest.hmac` + the shared webhook signing helper). Reset `CountingFakeProvider` and the clock in `@AfterEach`: the Spring context and the fake are shared by all ITs and others assert absolute call counts. Count: HTTP 500s, `webhook_events` rows still `RECEIVED`. Run against current code, record numbers in the note (expected > 0; probabilistic, so the count is evidence, the assertion comes after the fix).
4. Write `docs/learning/06-optimistic-locking.md` (VN prose, EN terms): lost update, read-modify-write, how `@Version` turns it into `UPDATE .. WHERE version = ?`, why the loser must re-read not re-send, why a retry needs a fresh transaction, the 2-transition bound, optimistic vs "last write wins". Include demo numbers.
5. Implement `OptimisticRetry` + unit test.
6. Wrap the 4 call sites. In the webhook path keep the existing `IllegalPaymentTransitionException` catch outside the retry.
7. Add the 503 handler.
8. Turn demo B into the acceptance test. Invariants for every payment: no 5xx; exactly one terminal row in `payment_transitions`; `payments.status` equals that row's `to_status`; no `webhook_events` row in `RECEIVED`; loser's response is the documented business outcome.
9. `./mvnw verify` (all existing tests stay green).
10. Note ends with "Tự phá thử": set attempts to 1, rerun `WebhookVsCancelRaceIT`, watch 500s return; then remove `@Version` and observe both transitions "succeed" (two terminal audit rows).

## Todo List
- [x] Branch + tag + compose snapshot
- [x] Demo A, demo B recorded
- [x] Note 06
- [x] `OptimisticRetry` + unit test
- [x] 4 call sites wrapped, 503 handler
- [x] Race IT asserts invariants
- [x] `./mvnw verify` green

## Success Criteria
- [x] `OptimisticLockConflictIT` reproduces the conflict on every run (no sleeps, latches only).
- [x] `WebhookVsCancelRaceIT`: 0 responses with 5xx, 0 events in `RECEIVED`, exactly one terminal transition per payment, over 200 iterations.
- [x] Before/after numbers in note 06 (before: 199/200 races with 5xx, 195 events stuck).
- [x] Full `./mvnw verify` green.

## As-built notes
- Counter increments once per actual retry (not on the final failed attempt); exhaustion logs a WARN.
- Race IT also asserts the retry counter moved, so it cannot pass without a real conflict. Phase 2 must re-check this assertion in pessimistic mode.
- Review: `reports/from-code-reviewer-to-cook-phase-01-optimistic-retry-review-report.md` (no Critical/High/Medium; Low items applied).

## Risk Assessment
- Exception type/timing differs on Hibernate 7 -> step 2 pins it empirically before the helper is written.
- Race IT is probabilistic before the fix -> only the post-fix invariants are asserted; they must hold on every interleaving.
- Create tx2 losing to `failAbandonedCreated` (needs a stall > 2 min between tx1 and tx2; provider timeouts 2s+5s cannot cause it): retry then yields `409 PAYMENT_ILLEGAL_TRANSITION`, which the idempotency filter stores for 24h. **Behaviour change** (was 500, key released). Documented in note 06 section 4; left for reconciliation (increment 4).

## Security Considerations
- No new endpoint or input. 503 body uses the existing problem-detail writer; no internal exception text leaked.

## Next Steps
- Phase 2 reuses the "retryable webhook" idea for outcomes that a re-read cannot fix (payment not ready yet).
