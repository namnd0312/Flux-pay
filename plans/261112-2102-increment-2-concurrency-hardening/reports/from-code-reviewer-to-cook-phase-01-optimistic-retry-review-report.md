# Phase 1 review: optimistic-lock retry

Date: 2026-11-12. Branch `feat/concurrency-hardening`, uncommitted diff vs `main` + untracked files. Read-only review.

## Verdict

No Critical / High / Medium defects. Phase 1 requirements are met by the code. 7 Low findings (2 doc/plan inaccuracies, 1 behaviour nuance the plan mislabels, 4 optional hardening items).

## Scope

- Main: `OptimisticRetry.java` (new), `PaymentOrchestrator.java`, `WebhookIngestionService.java`, `ProblemDetailsHandler.java`
- Tests: `OptimisticRetryTest`, `OptimisticLockConflictIT`, `WebhookVsCancelRaceIT` (new); `AbstractIntegrationTest`, `WebhookIngestionIT` (modified)
- Docs: `docs/learning/06-optimistic-locking.md`; `.gitignore`
- Re-run by reviewer: `./mvnw -q -pl gateway verify -Dtest=OptimisticRetryTest -Dit.test='OptimisticLockConflictIT,WebhookVsCancelRaceIT,WebhookIngestionIT'` -> exit 0 (one run, not a flake soak).
- Not done: did not read Spring/Hibernate jar source; commit-translation statement below rests on the two ITs plus framework knowledge.

## (a) Requirements vs code

| Requirement | Result | Evidence |
|---|---|---|
| Webhook vs cancel: one wins, loser gets business answer, no 500 | Met | `PaymentOrchestrator.java:146`, `WebhookIngestionService.java:131-139`; race IT invariants |
| Event never left RECEIVED by a lock conflict | Met | retry sits inside the `IllegalPaymentTransitionException` catch; tx C always runs unless retry is exhausted |
| Exhaustion -> 503 `CONCURRENT_UPDATE`, no `Retry-After`, key released | Met | `ProblemDetailsHandler.java:57-61`; `IdempotencyFilter.java:45` storable set = 201,400,404,409,422,502 |
| Webhook exhaustion -> 503 + RECEIVED until phase 2 | Met (as planned) | exception propagates out of `validateAndApply`, tx C skipped |
| Counter `fluxpay.payment.optimistic.retries`, tag `path` | Met | `OptimisticRetry.java:34,54`; four paths = create/cancel/webhook/compensate |
| Sweeper not retried | Met | `PaymentOrchestrator.java:178` still `newTx.execute` with per-row catch |
| 4 call sites wrapped, tx1 untouched | Met | lines 95 (tx1 plain), 115, 126, 146; webhook 131 |
| Branch, tag `increment-1-done`, compose snapshot, `.gitignore` | Met | tag exists; `docker-compose.increment-1.yml` exists and is ignored |
| Before/after numbers in note 06 | Met | note lines 11-12 |
| Postman folder "Phase 7 - Optimistic lock" | Not done | note line 95 explains why (Postman cannot fire two concurrent requests). Plan deviation, see L6 |

## (b) Regression walk

- **Re-read inside lambda:** yes at all four sites. create tx2 / compensate / cancel use `findByIdAndMerchantId` inside the supplier; webhook uses `loadForSweep(paymentId)` inside. The pre-read `found` in `validateAndApply` (line 115) is used only for immutable fields (id, amount). `open-in-view: false` (`application.yml:18`), so each attempt gets a fresh EntityManager; no stale managed instance can be returned by the re-read.
- **Non-idempotent side effects on retry:** none. Lambdas contain only entity mutation + `transitions.save` (audit row, same tx, rolls back with it). Provider call is outside tx2 and outside the retry (`PaymentOrchestrator.java:104`). `PaymentStateMachine.transition` has no logging/metrics/events. Only out-of-tx effect is the retry counter itself.
- **Exception type at commit:** no caller is `@Transactional` (only `IdempotencyService`, which is not on these paths), sweeper is not wrapped, OSIV off -> every attempt is an outermost transaction, so `UnexpectedRollbackException` (participating tx marked rollback-only) cannot occur. `JpaTransactionManager` translates the commit-time `RollbackException` cause to a `DataAccessException`; `TransactionSystemException` only if the cause is untranslatable. Empirically: `OptimisticLockConflictIT:104` pins `ObjectOptimisticLockingFailureException` at the `TransactionTemplate` boundary, and the race IT gets 0 5xx through the real HTTP paths. No Postgres deadlock: audit insert takes `FOR KEY SHARE` on the payment row, which does not conflict with the non-key UPDATE.
- **503 handler effect on existing paths:** only `Payment` carries `@Version`; no Spring Data `delete(entity)`/merge path that could raise the same exception elsewhere. Spring picks the most specific `@ExceptionHandler`, so declaration order vs the catch-all is irrelevant. Only one advice class. Paths that previously returned 500 for this exception now return 503; both non-storable, so idempotency behaviour is unchanged there. Webhook path: 500 -> 503 on exhaustion only.
- **Bound (3 attempts):** verified. `version` moves only via `PaymentStateMachine.transition` (`setStatus` + `touch`) and the provider setters in create tx2, which are in the same tx as the PENDING transition. No `@Modifying` / native UPDATE on `payments`.

One behaviour nuance: see L1.

## (c) Public contract

- New response: `503 CONCURRENT_UPDATE` (planned). No existing status/body changed on reachable paths.
- Constructors of `PaymentOrchestrator` and `WebhookIngestionService` gained `OptimisticRetry`; no manual `new` anywhere in main or test, Spring wiring only.
- No config key added or changed. `MeterRegistry` comes from the existing actuator starter.
- No API error catalogue exists in `docs/` or `README.md` to update.

## (d) Patterns

Follows them: `TransactionTemplate` built in constructor and passed in, explanatory comments in the existing style, no plan/phase IDs in code or test names, new ITs extend `AbstractIntegrationTest`, reset clock + fake in `@AfterEach` like `ConcurrentCreateIT`/`IdempotencyIT`. Shared webhook helpers moved to the base class with no behaviour change in `WebhookIngestionIT`. No findings.

## (e) Test quality

- **`OptimisticLockConflictIT`: deterministic.** Latches only; order forced as slow-read -> fast read+commit -> slow write. A failure in the fast writer surfaces through `fast.get` before the slow writer's 10 s latch timeout; pool is shut down in `finally`. Relies on `loadForSweep` staying a non-locking read; phase 2 plan keeps it that way (adds a separate `findByIdForUpdate`). If someone later puts `@Lock` on `loadForSweep`, this test hangs 10 s then fails, which is an acceptable signal.
- **`WebhookVsCancelRaceIT`: no flake vector found; invariants hold on every interleaving** (cancel fully first, webhook fully first, true overlap). No state leak: all 200 payments end terminal, order ids / idempotency keys / event ids are random, nothing asserts global row counts, `PaymentLifecycleIT:117` uses `>= 1` for sweep counts so even a mid-run failure leaving PENDING rows does not break other ITs. Pool of 2 with a 2-party barrier cannot starve; Hikari max 20. Weakness: see L4.
- **`OptimisticRetryTest`: tests the real class**, not a tautology. Stub template only counts transactions. Gaps: see L5.

## (f) Note 06 vs code

Accurate except two points: see L2, L3.

## Findings

### Real defects (all Low)

**L1. Create losing to the sweeper now yields a stored 409, not a released 500; plan calls this "pre-existing"**
- `PaymentOrchestrator.java:115-122`; plan `phase-01-...md` Risk Assessment, third bullet.
- Before: tx2 loses to `failAbandonedCreated` -> lock exception -> 500 -> idempotency key released -> merchant retry with the same key creates a new payment (old row is FAILED, not live).
- After: retry re-reads FAILED -> `IllegalPaymentTransitionException` -> `409 PAYMENT_ILLEGAL_TRANSITION` on `POST /payments`, and 409 is in `STORABLE_STATUSES`, so the same key replays that 409 for 24 h. Provider has already accepted the payment.
- Scenario: more than 2 min between tx1 and tx2 with the sweeper running in the gap (JVM stall, suspended container, laptop sleep in dev). Provider timeouts 2 s + 5 s make it unreachable through a slow provider alone.
- Fix: not required for this phase. Correct the plan wording ("pre-existing" is not exact), and decide deliberately. Options: (1) leave and document in note 06 section 4; (2) in `create`, catch `IllegalPaymentTransitionException` from tx2 and rethrow as a non-storable outcome. Plan owner's decision, not reversed here.

**L2. Note 06 exercise 1 states the wrong status**
- `docs/learning/06-optimistic-locking.md:99`: says with `MAX_ATTEMPTS = 1` "lỗi 500 ... quay lại".
- With the new handler the exhausted retry maps to **503 `CONCURRENT_UPDATE`**, not 500. The race IT still goes red (it checks `>= 500`), but a learner looking for 500 will be confused.
- Fix: say 503 `CONCURRENT_UPDATE` (5xx) returns.

**L3. "At commit" timing is stated but not pinned; plan note still [UNVERIFIED]**
- `docs/learning/06-optimistic-locking.md:16` says the exception fires "lúc commit (flush)". `OptimisticLockConflictIT:104` pins only the class. Plan step 2 asked to record flush-vs-commit and fix the `[UNVERIFIED]` marker in Key Insights; the marker is still there.
- The statement is very likely right (nothing forces a flush before commit). Fix: either add one assertion that the callback body completed before the failure (set a flag after `stateMachine.transition`), or soften the sentence; update the plan note.

### Optional improvements (Low)

**L4. Race IT can pass without any race happening**
- `WebhookVsCancelRaceIT.java:121-124`. Nothing asserts that a conflict occurred. If a later change serialises the two writers (phase 2 adds a pessimistic lock on the webhook path), this test keeps passing while no longer exercising `OptimisticRetry`.
- Fix: read `fluxpay.payment.optimistic.retries` from `MeterRegistry` before/after and assert delta > 0 (pre-fix evidence was 199/200 conflicts, so `>= 1` is safe), or at least put the delta in the assertion description.

**L5. Unit test gaps**
- `OptimisticRetryTest.java:52-58` does not assert the counter on exhaustion; no case uses the subclass Spring actually raises (`ObjectOptimisticLockingFailureException`). Commit-time failure (thrown by the template, not the callback) is covered only by the ITs; a stub that throws from `execute` after the callback would cover it in the unit test.

**L6. Counter semantics and silent exhaustion**
- `OptimisticRetry.java:54`: increments on the final failed attempt too, so exhaustion records 3 "retries" for 2 actual retries. Note 06 line 79 describes it as "each loss", which matches the code; the metric name does not. Either move the increment after the `attempt == MAX_ATTEMPTS` check or leave and keep the doc wording.
- `ProblemDetailsHandler.java:57-61` and `OptimisticRetry.java:55-57`: exhaustion is declared unreachable, yet if it happens there is no log line, only the counter. One `log.warn` with path at the rethrow would make a broken bound visible. Counter is also not registered until the first conflict, so dashboards show no series rather than 0.

**L7. Housekeeping before commit**
- `plans/261106-2236-mini-payment-gateway-increment-1-core-lifecycle/plan.md` is modified in the working tree (increment 1 acceptance tick). Unrelated to this phase; commit separately.
- Phase 1 file: todo list and success criteria still unchecked, status `in-progress`, Postman step not done (deviation explained only in the note). Lead/planner to update.

### Checked, no finding

- `compensateToFailed` can still replace the original `ProviderException` if it throws; same shape as before the change.
- `.gitignore` line is correct and scoped.
- No secrets, PII, or stack traces in the 503 body; fixed detail text.
- No N+1, no new queries; retry has no backoff, which is fine given the 2-transition bound.

## Recommended actions

1. Fix note 06 line 99 (503, not 500). L2
2. Correct the plan's "pre-existing" wording for create-vs-sweeper and record the chosen option. L1
3. Resolve the `[UNVERIFIED]` marker / commit-timing claim. L3
4. Optional: counter-delta assertion in the race IT; exhaustion log; unit test additions. L4-L6
5. Keep the increment 1 plan edit out of the phase 1 commit; update phase 1 todo state. L7

## Unresolved questions

- L1: is a stored 409 on create acceptable for the stall case, or should create tx2 map it to a non-storable outcome?
- Should the Postman step be removed from the phase 1 plan, given the note's rationale?

Status: DONE_WITH_CONCERNS
Summary: Phase 1 code meets its requirements; no Critical/High/Medium defects, retry is safe at all four call sites and the tests are sound. Seven Low items, mainly one wrong status in the learning note and a create-vs-sweeper outcome the plan mislabels as pre-existing.
Concerns/Blockers: none blocking.
