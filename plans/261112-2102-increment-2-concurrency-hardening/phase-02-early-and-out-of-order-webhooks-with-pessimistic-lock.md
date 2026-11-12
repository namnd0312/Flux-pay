---
phase: 2
title: Early and Out-of-Order Webhooks with Pessimistic Lock
status: completed
priority: P1
dependencies:
  - 1
effort: 2.5 days
---

# Phase 2: Early and Out-of-Order Webhooks with Pessimistic Lock

## Context Links
- Research: `research/researcher-02-fencing-webhooks-jmeter-report.md` (sections 2-3), `research/researcher-01-locking-and-retry-report.md` (Q3-Q5)
- Red team: `reports/from-code-reviewer-to-planner-red-team-*-plan-review-report.md`
- Code: `gateway/.../webhook/{WebhookIngestionService,WebhookEventStore,WebhookController,WebhookResult}.java`, `payment/PaymentRepository.java`, `mock-provider/.../webhook/WebhookSender.java`, `config/{MockProperties,AsyncConfig}.java`, `session/SessionController.java`
- Prior note: `docs/learning/03-webhook-ipn.md`

## Overview
Three lessons on the webhook path:
1. **Early webhook:** a webhook that arrives before create tx2 commits is recorded `REJECTED` (`UNKNOWN_PAYMENT`, because `provider_ref` is not stored yet) and answered 200. The dedup row is burned, so the success is lost for good. Fix: a *retryable* outcome (503, event row reclaimable) plus a provider that retries.
2. **Out-of-order:** FAILED then SUCCESS for one payment. First terminal wins; prove and document it.
3. **Pessimistic lock:** serialize writers on the webhook path with `SELECT ... FOR UPDATE`, switchable so phase 4 can compare it with optimistic+retry.

<!-- Updated: Red Team 2026-11-12 - single-row fallback decision, 503 for in-flight duplicates, guarded event updates, lock-timeout mechanism, mock retry semantics, test fixtures -->

## Key Insights
- Waiting on a row lock cannot fix the early webhook: during the provider HTTP call no transaction is open, tx2 has not started, nothing to wait on. The only sync fix is "tell the provider to come back".
- `merchantRef` in the event is our payment id, and that row exists from tx1. It lets us tell "not ready yet" (retryable) apart from "truly unknown" (permanent reject).
- The fallback must decide from **one row read**. Two separate reads (by ref, then by id) with tx2 committing in between would see PENDING and wrongly reject for good.
- HTTP status is the retry signal. 200 = "stop sending". So a duplicate of an event whose first delivery is still running (`RECEIVED`) must NOT get 200.
- Do NOT apply CREATED -> SUCCEEDED directly: tx2 would then fail PENDING and the create call would return an error for a paid order. State machine table stays unchanged.
- `webhook_events.processing_status` is `VARCHAR(20)` with no CHECK constraint (V4) and nothing else reads its values: `RETRYABLE` needs no migration.
- Reclaiming a retryable event is a conditional UPDATE (same arbiter pattern as the idempotency lease take-over).
- Lock timeout: Hibernate 7.4.13 ships PostgreSQL lock-timeout support (`PostgreSQLLockingSupport`, emits `set local lock_timeout`). Whether a `jakarta.persistence.lock.timeout` query hint on a Spring Data `@Lock` query triggers it is **[UNVERIFIED]**: step 9 tries it first. Fallback is `select set_config('lock_timeout', :ms, true)`; plain `SET LOCAL ... = :param` is a syntax error (utility statements take no bind parameters). `FOR UPDATE` vs `FOR NO KEY UPDATE` and the exact timeout exception class are **[UNVERIFIED]**: log SQL, pin empirically.

## Requirements
- Functional (first delivery):
  - Payment resolved by `(providerCode, providerRef)`: unchanged path.
  - Not resolved by ref -> load by `merchantRef` (UUID parse; reuse the system-path `loadForSweep`, no bare `findById`), decide from that single row:
    | Row state | Outcome | HTTP |
    |---|---|---|
    | no row / not a UUID | `REJECTED UNKNOWN_PAYMENT`, `payment_id` null | 200 |
    | `CREATED` | `RETRYABLE PAYMENT_NOT_READY` | 503 |
    | `provider_ref` equals event ref (tx2 committed meanwhile) | continue normal path | 200 |
    | terminal with `provider_ref` null (create compensated / abandoned) | `REJECTED PAYMENT_NEVER_ACCEPTED`, `payment_id` stored | 200 |
    | `provider_ref` differs | `REJECTED REF_MISMATCH`, `payment_id` stored | 200 |
  - Unexpected `RuntimeException` after the event insert: best-effort mark `RETRYABLE` (`INTERNAL_ERROR`), respond 500.
  - Lock wait timeout (pessimistic mode) or optimistic-retry exhaustion: `RETRYABLE` (`LOCK_TIMEOUT` / `CONCURRENT_UPDATE`), 503.
  - 503 body: code `WEBHOOK_RETRY_LATER`, header `Retry-After: 1`.
- Functional (redelivery of a known `event_id`):
  | Stored status | Body equals stored `raw_body` | Outcome | HTTP |
  |---|---|---|---|
  | `RETRYABLE` | yes | reclaim (exactly one winner), process again | per outcome |
  | `RETRYABLE` | no | `duplicate_count++`, response `rejectReason=EVENT_BODY_MISMATCH`, row untouched | 200 |
  | `RECEIVED` (first delivery in flight) | any | `WEBHOOK_RETRY_LATER` | 503 |
  | `APPLIED` / `REJECTED` | any | duplicate, `duplicate_count++` | 200 |
- FAILED then SUCCESS (distinct event ids): payment stays FAILED, second event `REJECTED ILLEGAL_TRANSITION`. Symmetric for SUCCESS then FAILED.
- mock-provider: retry the first delivery until a 2xx; retry only on 5xx, 429, IO error/timeout (never 4xx like 401/404/413); same `eventId`, same bytes; bounded attempts; total backoff window > 7s (gateway's worst-case provider call 2s + 5s). `duplicates` are extra single sends after the first success, not retried.
- Non-functional: `fluxpay.webhook.lock-mode: optimistic | pessimistic` (default `pessimistic`), `fluxpay.webhook.lock-timeout: 2s`. One row locked per transaction.

## Architecture
```
process(provider, rawBody, headers)
  verify signature -> parse
  tx A: insertNew(...)                               -> id   (first delivery)
        else reclaimRetryable(pc, eventId, rawBody)  -> id   (redelivery of RETRYABLE, same bytes)
        else read status: RECEIVED -> 503 | RETRYABLE (bytes differ) -> 200 mismatch | else 200 duplicate
  step 3: validateAndApply  -> APPLIED | REJECTED(reason) | RETRYABLE(reason)
          other RuntimeException -> try markRetryable(INTERNAL_ERROR); rethrow (500)
  tx C: markApplied | markRejected | markRetryable      (all: ... and processing_status = 'RECEIVED')
  HTTP: 200 | 503

tx B (inside OptimisticRetry, path "webhook"):
  pessimistic: findByIdForUpdate(id) with lock timeout   | optimistic: loadForSweep(id)
  stateMachine.transition(...)
catch IllegalPaymentTransitionException      -> REJECTED(ILLEGAL_TRANSITION)
catch PessimisticLockingFailureException     -> RETRYABLE(LOCK_TIMEOUT)      (not retried by OptimisticRetry)
catch OptimisticLockingFailureException      -> RETRYABLE(CONCURRENT_UPDATE) (after 3 attempts)
```
`WebhookEventStore`:
```sql
-- reclaimRetryable
update webhook_events
set processing_status = 'RECEIVED', reject_reason = null, processed_at = null,
    duplicate_count = duplicate_count + 1
where provider_code = :pc and event_id = :eid
  and processing_status = 'RETRYABLE' and raw_body = :raw
returning id;
-- findStatus(pc, eid) -> processing_status, (raw_body = :raw) as same_body
-- markRetryable(id, paymentId, reason, now); markApplied also sets reject_reason = null
-- every mark*: ... where id = :id and processing_status = 'RECEIVED'
```
`PaymentRepository`:
```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "2000"))  // if honoured; else set_config
@Query("select p from Payment p where p.id = :id")
Optional<Payment> findByIdForUpdate(@Param("id") UUID id);
```
If the hint must be dynamic or is not honoured: `select set_config('lock_timeout', :ms, true)` (`Duration.toMillis()` as text) as the first statement of tx B.

Coexistence to teach: the webhook holds the row lock; a concurrent cancel (still optimistic) blocks on its UPDATE, then fails the version check, retries (phase 1), and gets 409. Locks order writers; `@Version` still catches whoever did not take the lock.

mock-provider knobs (env-driven, like the existing ones):
| Key | Default | Purpose |
|---|---|---|
| `mock.callback.retry.max-attempts` | 6 | first delivery, retry on 5xx / 429 / IO only |
| `mock.callback.retry.backoff-ms` | 500 | doubled each attempt (0.5+1+2+4+8 = 15.5s window) |
| `mock.callback.auto-pay` | `NONE` | `SUCCESS`/`FAILED`: fire the webhook when the session is created (early-webhook race) |
| `mock.create.delay-ms` | 0 | hold the create-session response (widens the race; phase 4 "slow provider") |
| `mock.executor.core` / `max` / `queue` | 16 / 64 / 1000 | webhook sender pool (today hard-coded 2 / 4 / 50, abort on full) |
Sender keeps counters `sent`, `retried`, `exhausted`, `rejected` (executor full), logged and exposed as Micrometer counters `mock.webhook.*`. A rejected task must be logged and counted, never a silent 500 from `/mock/v1/pay`.

## Related Code Files
- Modify (gateway): `webhook/WebhookIngestionService.java`, `webhook/WebhookEventStore.java`, `webhook/WebhookResult.java` (add `retryable`), `webhook/WebhookController.java` (return `ResponseEntity`, 503 + `Retry-After`), `payment/PaymentRepository.java`, `src/main/resources/application.yml`. Update the "always 200" Javadoc in `WebhookController`, `WebhookResult`, `WebhookIngestionService`.
- Modify (mock-provider): `webhook/WebhookSender.java`, `config/MockProperties.java`, `config/AsyncConfig.java`, `session/SessionController.java`, `src/main/resources/application.yml`
- Modify (tests): `gateway/src/test/java/com/fluxpay/gateway/CountingFakeProvider.java`, `AbstractIntegrationTest.java`, `mock-provider/.../webhook/WebhookSenderTest.java`, `mock-provider/.../session/SessionControllerTest.java` (`@WebMvcTest` slice needs the new `WebhookSender` / `MockProperties` dependencies)
- Create: `gateway/src/test/java/com/fluxpay/gateway/webhook/EarlyWebhookIT.java`, `OutOfOrderWebhookIT.java`, `WebhookPessimisticLockIT.java`, `WebhookOptimisticModeRaceIT.java` (subclass of the phase 1 race IT with `@TestPropertySource(lock-mode=optimistic)`)
- Create: `docs/learning/07-webhook-races-and-pessimistic-lock.md`
- Modify: `docs/learning/03-webhook-ipn.md` (the "always 200" statement -> pointer to note 07)
- Modify (local, git-ignored): `docker-compose.yml` (env passthrough), `postman/flux-pay.postman_collection.json` (folder "Phase 8 - Webhook races")

## Implementation Steps
1. **Test fixtures first.** `CountingFakeProvider.blockNextCreate()` returns a handle: `awaitEntered()`, `merchantRef()` (captured from the request; provider ref is then `mp_fake_<id>`), `release()`. `reset()` releases any pending latch. Every new IT resets fake + clock in `@AfterEach`. (Webhook signing helper already moved to `AbstractIntegrationTest` in phase 1.)
2. **Failure demo (early webhook):** `EarlyWebhookIT` against current code. Park create; POST signed SUCCESS with `merchantRef` = payment id: 200 `REJECTED UNKNOWN_PAYMENT`. Release (PENDING). Re-POST same bytes: `duplicate=true`. Payment stays PENDING although "the customer paid". Record in note.
3. Write `docs/learning/07-webhook-races-and-pessimistic-lock.md` (VN): at-least-once + no ordering (Stripe docs as reference), dedup-before-validate trap, permanent vs retryable rejection, HTTP status as retry signal (incl. why in-flight duplicate = 503), check-then-act across two reads, first-terminal-wins, optimistic vs pessimistic table, what a row lock does and does not solve, lock timeout, known limits.
4. `WebhookEventStore`: `markRetryable`, `reclaimRetryable`, `findStatus`, status guards, `markApplied` clears reason.
5. `WebhookIngestionService`: flow above (single-row fallback table); `WebhookResult.retryable`; controller maps to 503.
6. mock-provider: retry loop (status-aware), `auto-pay`, `create.delay-ms`, configurable executor + rejection handler + counters. Extend `WebhookSenderTest` (retries on 503, not on 401, stops on 200, stops at max attempts, duplicates sent once after success); fix `SessionControllerTest`.
7. Acceptance tests in `EarlyWebhookIT`:
   - parked create: POST -> 503 + `RETRYABLE`; release; re-POST -> applied, SUCCEEDED, one terminal audit row, `reject_reason` null.
   - **tx2-commits-between case:** not reachable with latches end to end, so keep the fallback decision in one package-private method that takes the loaded `Payment` + event, and test all five rows of the fallback table directly (PENDING + equal ref -> continue and apply, never `UNKNOWN_PAYMENT`).
   - create compensated to FAILED (fake throws `ProviderException`), then webhook -> `REJECTED PAYMENT_NEVER_ACCEPTED` with `payment_id` set.
   - redelivery while `RECEIVED` -> 503; redelivery of `RETRYABLE` with a different body -> 200 `EVENT_BODY_MISMATCH`, row unchanged.
   - two concurrent redeliveries of a `RETRYABLE` event -> exactly one applies; the other gets 503 or duplicate; payment has one terminal row.
8. `OutOfOrderWebhookIT`: FAILED then SUCCESS; SUCCESS then FAILED.
9. Pessimistic arm. Try the query hint; read the SQL log; record `FOR UPDATE` form and whether `lock_timeout` is emitted; fall back to `set_config` if not. `WebhookPessimisticLockIT` (own context via `@TestPropertySource`, `lock-timeout=300ms` for case b):
   - (a) test tx holds `FOR UPDATE`; webhook thread starts; **poll `pg_stat_activity` until a backend shows `wait_event_type = 'Lock'`**; commit -> applied.
   - (b) lock held past the timeout -> 503 `LOCK_TIMEOUT`, event `RETRYABLE`; redelivery applies. Record the exception class; assert on the Spring `PessimisticLockingFailureException` hierarchy.
   - (c) after a timed-out call, the next transaction on the pool has the default `lock_timeout` (setting did not leak).
10. Race IT in both modes: the phase 1 `WebhookVsCancelRaceIT` runs with the default (pessimistic); `WebhookOptimisticModeRaceIT` subclass runs it with `lock-mode=optimistic`.
11. `./mvnw verify`. Postman folder. "Tự phá thử": change `reclaimRetryable` to select-then-update and run the concurrent redelivery test.

## Todo List
- [x] Fixture hook + reset discipline
- [x] Early-webhook demo recorded
- [x] Note 07, note 03 pointer
- [x] Retryable outcome, single-row fallback, guarded event updates
- [x] mock-provider retry / auto-pay / create delay / pool / counters
- [x] `EarlyWebhookIT`, `OutOfOrderWebhookIT` green
- [x] Pessimistic arm + `WebhookPessimisticLockIT`
- [x] Race IT green in both lock modes
- [x] `./mvnw verify` green

## Success Criteria
- [x] Early webhook ends SUCCEEDED after redelivery; before/after recorded in note 07.
- [x] Fallback decides from one row; PENDING with matching ref is applied, never `UNKNOWN_PAYMENT`.
- [x] In-flight duplicate gets 503; `RETRYABLE` reclaim has exactly one winner and requires identical bytes.
- [x] Out-of-order pair never changes a terminal payment.
- [x] Lock timeout produces 503 + reclaimable event; timeout setting does not leak across pooled connections.
- [x] `WebhookVsCancelRaceIT` invariants hold in `optimistic` and `pessimistic` modes.
- [x] Full `./mvnw verify` green (gateway + mock-provider).

## As-built notes (deviations)
- Lock SQL on PostgreSQL / Hibernate 7.4 is `SELECT ... FOR NO KEY UPDATE` (verified from the SQL log), not `FOR UPDATE`.
- Lock timeout set with `select set_config('lock_timeout', :ms, true)` as a repository native query (same connection guaranteed). The JPA hint experiment was skipped: an annotation value cannot come from config. Timeout surfaces inside Spring's `PessimisticLockingFailureException` hierarchy (verified by the timeout IT).
- Payment-side logic split out of `WebhookIngestionService` into `WebhookPaymentApplier` + `WebhookOutcome` (file size, clearer boundary).
- 503 body carries `code` plus a `reason` property (`PAYMENT_NOT_READY`, `LOCK_TIMEOUT`, `CONCURRENT_UPDATE`, `EVENT_IN_PROGRESS`).
- Added `REJECTED MALFORMED_EVENT` for a signed event with a blank reference or unknown status (review finding).
- Mock sender pool knobs are `mock.executor.threads` / `queue` (32 / 2000): core = max, because a separate max is never used with a large queue. `auto-pay` value validated at startup.
- Optimistic-mode race IT is `payment/WebhookVsCancelOptimisticModeRaceIT`; in pessimistic mode the race IT asserts the webhook path had 0 version conflicts.
- `WebhookPessimisticLockIT` uses one context with `lock-timeout=1500ms`.
- Not done: end-to-end `docker compose` demo (running local stack uses db host port 5433 while the compose file maps 5432; not restarted without the user's say) and the Postman folder.
- Review: `reports/from-code-reviewer-to-cook-phase-02-webhook-races-review-report.md` (no Critical/High; 4 Medium + L1-L4, L6 applied; L5, L7 left; L8 mock metrics exposure moves to phase 4).

## Risk Assessment
Known limits, documented in note 07, owned by increment 3 (inbox consumer) and increment 4 (reconciliation):
- **Paid but FAILED:** provider accepted and customer paid, but create timed out and compensated to FAILED (or JVM died before tx2 and the sweeper failed the row). Redelivery -> `REJECTED PAYMENT_NEVER_ACCEPTED` with the payment linked. FAILED is not "live", so the merchant can create the order again. Same money-taken shape for FAILED-then-SUCCESS.
- **Provider gives up:** event stays `RETRYABLE` after the retry window.
- **Event stuck `RECEIVED`:** JVM crash between tx A and tx C, or a DB failure that also defeats `markRetryable`/tx C. Redeliveries then get 503 until the provider gives up (no longer silently acked).
Other:
- 503 could make a provider hammer the endpoint -> `Retry-After` + backoff in the mock; real providers use their own schedule.
- Lock-mode toggle is comparison scaffolding -> revisit after phase 4 (keep or delete the losing arm).
- New Spring context per `@TestPropertySource` class (extra pool of 20 connections each): keep to the two classes named above.

## Security Considerations
- Signature check stays the first step; no write before it. A forged "early" webhook still gets 401 and no row.
- `merchantRef` fallback has no merchant scope: system path, signature already verified, used only to pick an outcome; never returns payment data.
- Reclaim requires byte-identical body, so the stored `raw_body` stays the audited signed bytes. No retry-attempt cap: only a holder of the provider secret can redeliver, and the provider's own schedule bounds it.
- 503 body carries only the stable code.

## Next Steps
- Phase 4 measures both lock modes, the slow-provider knob, and reads the mock counters.
