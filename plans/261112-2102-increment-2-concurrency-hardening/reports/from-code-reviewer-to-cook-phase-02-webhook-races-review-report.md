# Phase 02 webhook races: code review

Date: 2026-11-13. Branch `feat/concurrency-hardening`, uncommitted diff vs HEAD + untracked files. Read-only review.

## Scope
- Gateway main: `webhook/{WebhookIngestionService,WebhookPaymentApplier,WebhookOutcome,WebhookEventStore,WebhookResult,WebhookController}.java`, `payment/PaymentRepository.java`, `application.yml`
- Gateway test: `CountingFakeProvider`, `EarlyWebhookIT`, `OutOfOrderWebhookIT`, `WebhookPessimisticLockIT`, `WebhookFallbackDecisionTest`, `WebhookVsCancelRaceIT` + optimistic subclass
- mock-provider: `MockProperties`, `AsyncConfig`, `WebhookSender`, `SessionController`, yml, 3 tests
- Docs: `docs/learning/07-...md`, `03-webhook-ipn.md`
- Not re-run: `./mvnw verify` (caller reports green; taken as given). No file under `target/` read.
- Verified by bytecode (javap, local m2): Hibernate 7.4.13 `PostgreSQLDialect` maps SQLState 55P03 -> `org.hibernate.exception.LockTimeoutException` (extends `LockAcquisitionException` extends `PessimisticLockException`); spring-orm 7.0.10 `EntityManagerFactoryUtils` maps jakarta `LockTimeoutException` -> `CannotAcquireLockException`, `PessimisticLockException` -> `PessimisticLockingFailureException`; `CannotAcquireLockException extends PessimisticLockingFailureException`. `HibernateJpaDialect` not inspected (hook blocks the `vendor` path pattern); the passing timeout IT covers that leg.

## Overall
No Critical / High. Main-path concurrency design holds: one owner per event row at any time, the insert / conditional-reclaim arbiters are sound, no path returns 200 for an event that can later end RETRYABLE (except the specified body-mismatch row). Findings are one observability gap in the new error path, three tests weaker than their claim, and low-severity edges.

## Real defects

### Medium

**M1. Unexpected webhook failure is never logged**
- `gateway/.../webhook/WebhookIngestionService.java:86-91`, with `gateway/.../common/ProblemDetailsHandler.java:103-106`
- Catch calls `releaseForRedelivery(id)` and rethrows; the catch-all `@ExceptionHandler(Exception.class)` returns 500 without logging. `releaseForRedelivery` logs only when its own update fails.
- Scenario: deterministic bug in apply (NPE, `IncorrectResultSizeDataAccessException`, see L1). Every redelivery: row -> `RETRYABLE INTERNAL_ERROR`, 500, no stack trace anywhere. With the accepted "no attempt cap" this loops silently until the provider gives up.
- Fix: `log.error("Webhook event {} ({}) failed unexpectedly", id, event.eventId(), ex)` in the catch before release.

**M2. Lock-timeout "no leak" check very likely inspects one connection, and not the one that timed out**
- `gateway/src/test/.../webhook/WebhookPessimisticLockIT.java:118-122`
- HikariCP `ConcurrentBag.borrow` serves a thread from its thread-local list first. The 25 sequential borrows on the test main thread get the same connection each time (the one it last returned). The timed-out transaction ran on the single `pool` thread, on a different connection (main thread held its own for the lock tx). So the loop proves "committed tx on connection A did not leak", 25 times; plan step 9(c) "after a timed-out call ..." and note 07 line 124 "không rò sang connection khác trong pool" are not what is asserted.
- Not a product bug: `set_config(..., true)` is transaction-local by PostgreSQL contract. The test just cannot fail for the case it names.
- Fix: run the `current_setting('lock_timeout')` check on the `pool` executor thread (same connection affinity as the timed-out call) before shutdown, or borrow `maximum-pool-size` connections at once from the `DataSource` and check each.

**M3. Default-mode race IT proves neither "raced" nor "pessimistic"**
- `gateway/src/test/.../payment/WebhookVsCancelRaceIT.java:135-148`
- `expectsVersionConflicts()` false removes the only "this actually raced" guard in the default run. The class would pass unchanged if `lock-mode` were ignored, or if 200 iterations never overlapped.
- Fix: in pessimistic mode the webhook holds the row lock from read to commit, so it can never lose a version check. Assert `fluxpay.payment.optimistic.retries{path=webhook}` delta == 0 (proves the lock arm is active) and keep a race guard that fits the mode (e.g. `path=cancel` retries > 0, or both winners observed).

**M4. New error and continue paths have no test**
- `WebhookIngestionService.java:86-91,149-160` (`INTERNAL_ERROR` release + 500): zero coverage (`grep INTERNAL_ERROR` in tests: none). This is the path asked about in (b); behaviour reasoned below, not proven.
- `WebhookPaymentApplier.apply` "ref not found, then row is PENDING with equal ref -> continue **and apply**": `WebhookFallbackDecisionTest.java:53-61` asserts only that the pure function returns null. Plan step 7 says "continue and apply, never UNKNOWN_PAYMENT".
- Fix: Mockito unit test of `apply` (`findByProviderCodeAndProviderRef` -> empty, `loadForSweep` -> PENDING with ref) asserting applied; one test with an applier that throws, asserting 500 + row `RETRYABLE/INTERNAL_ERROR` + redelivery reclaimed.

### Low

**L1. Signed event without `providerRef` hits an `IS NULL` lookup** (pre-existing lookup, new consequence)
- `WebhookPaymentApplier.java:71`. Spring Data derived query with a null argument becomes `provider_ref is null`. Rows with null ref always exist (CREATED, compensated FAILED). Two or more -> `IncorrectResultSizeDataAccessException` -> now `RETRYABLE INTERNAL_ERROR` + 500 on every redelivery (before: one 500, then swallowed as duplicate). Exactly one -> event gets linked to an unrelated payment as `REF_MISMATCH`.
- Threat model: needs the provider secret; a provider bug, not an attacker. Fix: after parse, require `eventId`, `providerRef`, `merchantRef`, `status` non-blank; else `REJECTED MALFORMED_EVENT` (200), no lookup.

**L2. Unknown status string means FAILED** (unchanged check, on the by-ref path you asked about)
- `WebhookPaymentApplier.java:92`: anything not exactly `"SUCCESS"` -> FAILED (terminal). New trigger: `mock.callback.auto-pay` is a free string compared only against `NONE` (`SessionController.java:51-56`); `MOCK_CALLBACK_AUTO_PAY=success` sends `"success"` and the gateway fails a paid payment.
- Fix: bind `autoPay` as an enum (`NONE|SUCCESS|FAILED`, startup failure on anything else); gateway side reject unknown status as `UNKNOWN_STATUS`.

**L3. Ledger can say REJECTED for the event that actually applied**
- `WebhookIngestionService.java:86-91`. If tx B committed but the call still threw (commit ack lost, connection drop after commit): release -> `RETRYABLE`; redelivery -> payment already terminal -> `REJECTED ILLEGAL_TRANSITION`, 200. Payment state correct, applied once, no stuck row; only the event row misattributes. Safe, but document it in note 07 next to the reconciliation limits.

**L4. `releaseForRedelivery` erases the payment link**
- `WebhookIngestionService.java:152` passes `paymentId = null`; `markOutcome` writes it. A row that was `RETRYABLE PAYMENT_NOT_READY` with `payment_id` set loses it on a later `INTERNAL_ERROR`. Fix: `payment_id = coalesce(cast(:pid as uuid), payment_id)` or a dedicated update that leaves the column alone.

**L5. Guarded `mark*` ignores the update count**
- `WebhookEventStore.java:100-133`, caller `WebhookIngestionService.java:94-103`. Under the code as written 0 rows cannot happen (a RECEIVED row has exactly one owner: reclaim needs RETRYABLE, marks need RECEIVED). If it does (manual repair), the HTTP answer still comes from `outcome`, which is the right answer for the payment. Only gap: silent. Fix: return the count, `log.warn` when != 1.

**L6. `lock-timeout` and `lock-mode` edge values**
- `WebhookPaymentApplier.java:66`: `0s` or sub-millisecond -> `lock_timeout = 0` = wait forever, the exact thing the setting exists to prevent. Fix: reject `toMillis() <= 0` in the constructor.
- `application.yml:59`: env var set but empty (compose passthrough `${VAR:-}`) resolves to `""`, not the default -> startup failure. Message is clear (`must be 'optimistic' or 'pessimistic' but was ''`); fail-fast is acceptable, mention in the compose file.

**L7. Mock: 3xx counts as delivered; non-positive `max-attempts` counts "exhausted" with zero sends**
- `WebhookSender.java:103-122` (default status handler only raises on 4xx/5xx), `:83,98`. Plan says "until a 2xx". Fix optional: treat non-2xx as GIVE_UP; clamp `maxAttempts >= 1`.

**L8. Mock counters are not readable over HTTP**
- `mock-provider/src/main/resources/application.yml:13-17` exposes only `health`. Phase 4 "reads the mock counters": needs `metrics` exposure (or log scraping). `retried/exhausted/rejected` also do not exist until first increment (absent, not 0).

## Requirement tables (a)
- First-delivery fallback table: all 5 rows implemented as specified (`WebhookPaymentApplier.java:116-133`); `loadForSweep` reused, no bare `findById`. Extra provider-code equality on the "equal ref" row is stricter, fine.
- Unexpected exception -> best-effort `RETRYABLE INTERNAL_ERROR` + 500: implemented, untested (M4).
- Lock timeout / retry exhaustion -> `LOCK_TIMEOUT` / `CONCURRENT_UPDATE` + 503: implemented; `CONCURRENT_UPDATE` untested (unreachable in practice).
- 503 body `WEBHOOK_RETRY_LATER` + `Retry-After: 1`: implemented. Deviation: plan Security section says "503 body carries only the stable code"; body also has `reason`. Exposed only after a valid signature, same class of info as `rejectReason` on 200. Non-issue; align the plan text.
- Redelivery table: all 4 rows implemented (`WebhookIngestionService.java:125-140`, `WebhookEventStore.java:68-82`).
- Mock retry rules: implemented (5xx/429/IO retry, other 4xx stop, same id and bytes, 6 attempts, 15.5 s window, duplicates single-shot). Deviations: executor knobs are `threads`/`queue` 32/2000, not `core`/`max`/`queue` 16/64/1000 (reason in `AsyncConfig` comment is valid); optimistic IT is `payment/WebhookVsCancelOptimisticModeRaceIT`, not `webhook/WebhookOptimisticModeRaceIT`; lock-timeout in the IT is 1500 ms, not 300 ms.

## Concurrency / failure walk (b)
- Claim interleavings: concurrent first deliveries: second insert waits on the unique index, returns empty, reclaim misses (RECEIVED), `findExisting` -> 503. Status changing between reclaim and `findExisting`: becomes APPLIED/REJECTED -> 200 duplicate (final, correct); becomes RETRYABLE same body -> 503 (provider retries, correct). No stuck or double-applied state found. Double apply is additionally blocked by the state machine.
- 200 then RETRYABLE: not possible. 200 is returned only for APPLIED/REJECTED (never rewritten: every writer needs RECEIVED or RETRYABLE) and for the specified body-mismatch row.
- `mark*` affecting 0 rows: answer still right (L5).
- `releaseForRedelivery`: safe when tx B had committed (L3); cannot mask the original (catches its own `RuntimeException`, original rethrown). Step 4 failure itself is not released: inside the accepted "stuck RECEIVED" limit.
- `decideWithoutProviderRef`: all branches correct; CREATED checked before null-ref, so "not CREATED + null ref" is terminal by the create flow's invariant (`PaymentOrchestrator` sets ref and PENDING in one tx). By-ref path unchanged; see L1, L2.
- Pessimistic mode: `setLocalLockTimeout` + `findByIdForUpdate` both run inside the `retry.execute` lambda, i.e. same transaction, every attempt; declared query methods join the outer tx. Exception hierarchy verified (Scope). 40P01 deadlock also lands in the same hierarchy and is reported as `LOCK_TIMEOUT` (label only). `OptimisticRetry` catches only `OptimisticLockingFailureException`; the sibling pessimistic exception passes through unretried and is caught in `apply`, not swallowed. No nested connection use, so lock waits cannot deadlock the pool.
- 503 shape: `ProblemDetail` + `code` + `reason` + `Retry-After: 1` + `application/problem+json`. 200 body: same record, same fields, same converter; byte-compatible.

## Regressions (c)
No finding. `WebhookIngestionIT` untouched and its `UNKNOWN_PAYMENT` case still takes the no-row branch; no manual `new` of the changed classes outside `WebhookSenderTest` (updated); `IdempotencyFilter` and merchant API not in the diff; invalid `lock-mode` fails startup with a clear message; `application-test.yml` needs no new keys (inherits `application.yml`).

## mock-provider (d)
- Retry classification matches RestClient: 4xx/5xx -> `RestClientResponseException` subclasses, IO/timeout -> `ResourceAccessException` (a `RestClientException`). Correct. 429 and IO branches have no test.
- `@DefaultValue` on nested records: Boot creates the nested instance with its own defaults when the block is absent (documented behaviour). Not proven by a test here: both slices load `application.yml`, which sets every key.
- Rejection handler: handler does not throw, so `ThreadPoolTaskExecutor` raises no `TaskRejectedException`; the `@Async void` call returns normally, task dropped, counted, logged. As intended. Also fires on shutdown with a misleading "pool full" message (cosmetic).
- Thread-safety: sender is stateless, counters thread-safe. Auto-pay stores before sending. Slices are correct.
- Findings: L2, L7, L8.

## Test quality (e)
- `EarlyWebhookIT`: deterministic. Gate parks only the first create, `reset()` releases it, executor is per test instance. On a failed test the parked create may finish after `reset()` and bump `calls` for the next class (failure path only).
- `WebhookPessimisticLockIT` (a): poll + `pg_stat_clear_snapshot` correct; flake only if the gap between "waiter visible" and commit exceeds 1500 ms (starved CI). (b): deterministic, lock held until the webhook answers. Leak check: M2; it cannot legitimately observe a non-default value, so it cannot flake either.
- Race IT: M3. Optimistic subclass keeps the retries > 0 guard.
- Weaker than named: M2, M3, M4; `redeliveryWithDifferentBytes...` does not assert `duplicate_count`; `concurrentRedeliveries...` passes when the two calls never overlap (inherent, acceptable).

## Note 07 (f)
- Line 101: "insert audit row không bị chặn bởi chính loại khóa này" reads as if the webhook's own insert benefits. A transaction never blocks on its own lock; plain `FOR UPDATE` would not block it either. Who benefits is another transaction's child insert: cancel's audit insert (Hibernate flushes inserts before updates) passes its FK `FOR KEY SHARE` check and then waits at its UPDATE, which is exactly what line 114 describes. Reword.
- Line 114 (cancel UPDATE waits, then fails the version check, retry, 409): correct under READ COMMITTED (WHERE re-evaluated on the new row version, 0 rows).
- Line 124 "setting không rò sang connection khác trong pool": overclaims what the test checks (M2).
- Lines 3, and code comments `WebhookPaymentApplier.java:40`, `PaymentRepository.java:42`, `application.yml:57` say `FOR UPDATE`; real SQL is `FOR NO KEY UPDATE`. Repository Javadoc "any other transaction that wants to lock ... waits" is false for `FOR KEY SHARE`.
- Other statements (reclaim SQL, tables, SET LOCAL bind limitation, snapshot freeze, mock numbers) match the code.

## Structure (g)
No finding. All main files under 200 lines (max 169); no dead code. `answerForKnownEvent` empty-row branch is unreachable today (no deletes) but harmless.

## Optional improvements
- `new String(rawBody, UTF_8)` maps invalid byte sequences to U+FFFD, so "byte-identical" is "text-identical after decode". Irrelevant for JSON from a signed sender.
- `processed_at` uses the request-arrival instant, so it precedes a lock wait.
- Binder unit test for `MockProperties` with an empty environment.

## Plan follow-ups (for lead; plan files not edited)
All phase 2 todo items appear complete. Success criterion "timeout setting does not leak across pooled connections" is implemented but not actually demonstrated (M2).

## Unresolved questions
1. Should a signed event with missing fields be `REJECTED` (200) or 400? (L1)
2. Phase 4 needs the mock counters: expose `metrics` on the mock, or read logs? (L8)

Status: DONE_WITH_CONCERNS
Summary: No Critical/High; ingestion and lock paths are sound and every requirement row is implemented. Four Medium items: unexpected failures are never logged, and three tests assert less than they claim.
Concerns/Blockers: M1 worth fixing before commit (one line). M2-M4 are test strength, not product bugs.
