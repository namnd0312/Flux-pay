# Red-team plan review: Failure Mode Analyst (Flow Tracer)

Plan: `plans/261119-2323-increment-3-kafka-messaging/`. Method: read all 6 plan files, traced the current webhook/payment/sweeper/test paths in code. Nothing was built or run; findings 1, 4 rest on PostgreSQL/Hibernate semantics reasoned from the traced code, not on an executed repro.

Paths below are relative to `gateway/src/main/java/com/fluxpay/gateway/` unless they start with `gateway/` or `load-tests/`.

---

## Finding 1: Phase 4 notification insert (`unique(payment_id)`) turns the cancel-loses race into a 500 and adds a deadlock with the webhook row lock
- **Severity:** Critical
- **Location:** Phase 4, "Requirements" (first bullet) + "Architecture"; contradicts Phase 3 "Risk Assessment" (deadlock bullet)
- **Flaw:** The notification row is inserted by JdbcClient (immediate SQL) inside `transition`, but the payment `UPDATE` is only flushed at commit. So the unique index on `merchant_notifications.payment_id` is hit BEFORE the version check / row lock arbitrates. Cancel and sweeper take no row lock. Phase 3's "only this path takes two locks... cancel and sweeper take the payment only" stops being true in phase 4: every terminal writer now takes {unique-index entry, payment row} in the opposite order to the webhook consumer {payment row, unique-index entry}.
- **Failure scenario:**
  - A (webhook wins, common case): consumer locks payment row, transitions, inserts notification. Cancel read PENDING earlier (no lock), calls `transition`, its notification insert blocks on the consumer's uncommitted index entry; consumer commits; cancel gets a unique violation -> `DataIntegrityViolationException`, not an optimistic failure -> not retried -> 500. Today the same interleaving gives 409 via version retry.
  - B (deadlock): cancel inserts its notification row (uncommitted). Consumer takes the payment row lock, transitions, its notification insert waits on cancel's transaction. Cancel commits -> flush `UPDATE payments ... where version=?` waits on the consumer's row lock. Cycle. After `deadlock_timeout` (1s) PostgreSQL aborts one; cancel started waiting first so it is the likely victim -> `CannotAcquireLockException` -> 500 after a 1s stall. `ON CONFLICT DO NOTHING` fixes A but not B (it still waits on the in-progress insert).
  - Same shape for sweeper vs webhook and cancel vs sweeper.
- **Evidence:**
  - Plan phase 4: "`PaymentStateMachine.transition` ... inserts one `merchant_notifications` row ... in the caller's transaction. `unique(payment_id)` backs 'one terminal notification per payment'"; `MerchantNotificationStore` (JdbcClient). No conflict handling, no flush order stated.
  - Cancel reads without lock: `payment/PaymentOrchestrator.java:146-151` (`findByIdAndMerchantId`); sweeper: `:189-196` (`loadForSweep`). Webhook locks: `webhook/WebhookPaymentApplier.java:138-143`, `payment/PaymentRepository.java:48-50`.
  - `transition` only mutates the entity and calls `transitions.save` (persist, no flush): `payment/PaymentStateMachine.java:49-58`.
  - Only optimistic failures are retried: `payment/OptimisticRetry.java:57`; only `OptimisticLockingFailureException` has a handler, the rest fall to the generic 500: `common/ProblemDetailsHandler.java:57`, `:103`.
  - Guarantee that breaks: "cancel answers 200 or 409, never 5xx" `load-tests/webhook-vs-cancel.jmx:5`, `:114-115`; `gateway/src/test/java/com/fluxpay/gateway/payment/WebhookVsCancelRaceIT.java:113-115`, `:162-167`.
- **Suggested fix:** Make the payment row the first thing every terminal writer contends on: in `transition`, flush the payment update (`saveAndFlush`) before the notification insert, or have cancel/sweeper use `findByIdForUpdate`. Then use `ON CONFLICT DO NOTHING` only as a backstop. Add the cancel-vs-webhook race IT to phase 4's test list explicitly.

## Finding 2: Test base cannot isolate the new async actors: listeners are not gated by the scheduling flag, a second Spring context already exists, tables are never cleaned
- **Severity:** High
- **Location:** Phase 2, "Architecture" (tests bullet) + steps 5-6; Phase 1 "Architecture" (test base); Phase 4 step 4-5
- **Flaw:** The plan gates only the relay (`fluxpay.scheduling.enabled=false`, call `publishDue()`), by analogy with the sweeper. `@KafkaListener` containers are not gated and run in every cached context against one static broker and one static database.
- **Failure scenario:**
  1. Context A (default) is cached with its consumer in group `fluxpay-webhook-applier`. `WebhookPessimisticLockIT` builds context B (own property) -> second consumer joins the same group -> rebalance, partitions split A/B.
  2. Test in B posts a webhook, calls B's `publishDue()`; the record lands on a partition owned by A's consumer and is processed with A's beans: A's lock-timeout (2s, not 1500ms), A's `MutableClock`, A's `MeterRegistry`, A's `CountingFakeProvider`. Assertions on context-local observables become vacuous or flaky.
  3. Leftovers: no IT deletes rows. After phase 4 every terminal transition in every IT (200 payments in `WebhookVsCancelRaceIT` alone) leaves unpublished outbox rows. The next test's `publishDue()` (batch 100, `created_at` order) publishes foreign rows first; its own row may not be in the batch; foreign notification records POST to whatever `callback_url` an earlier test left on the shared DEMO merchant (dead local port -> 2s connect timeouts -> head-of-line on the partition the current test waits on).
  4. `BrokerOutageIT` "outbox unpublished = 0" and the phase 2 criterion "no ledger row without an outbox row" fail on foreign rows (one IT inserts a ledger row by raw SQL).
  5. Any new per-test override (1-partition topic in `SlowMerchantHeadOfLineIT`, a closed-port producer, shorter backoff) creates yet another context = another group member.
- **Evidence:**
  - Second context today: `gateway/src/test/java/com/fluxpay/gateway/webhook/WebhookPessimisticLockIT.java:42` (`@TestPropertySource`).
  - Static shared container, no cleanup hook: `gateway/src/test/java/com/fluxpay/gateway/AbstractIntegrationTest.java:49-55`; grep for `delete from|truncate|@Sql` in `gateway/src/test` returns nothing; `@AfterEach` only resets clock/fake (e.g. `EarlyWebhookIT.java:58-63`).
  - Flag gates only scheduling: `payment/SchedulingConfig.java:13-14`, `payment/PaymentExpirySweeper.java:21`; `gateway/src/test/resources/application-test.yml:23-25`.
  - Raw-SQL ledger row without outbox: `EarlyWebhookIT.java:118-120`.
  - Context-local guard that goes dead: `WebhookVsCancelRaceIT.java:139-148` asserts the `path=webhook` retry counter is 0; phase 3 removes the retry loop from the webhook path, so the counter can never move and the "row lock is in effect" guard proves nothing. Same test's "both sides must win" check (`:137-138`) depends on a barrier that no longer releases the applier (HTTP only inserts now).
- **Suggested fix:** Decide in phase 1: listeners off in the test profile (`autoStartup=false` / `spring.kafka.listener.auto-startup=false`) and tests call `WebhookEventProcessor` directly, with a small number of dedicated ITs that start the listener with a unique group id per context; add a `@BeforeEach` that truncates `outbox` (and resets `merchants.callback_url`); forbid per-class property overrides in listener ITs. Rewrite the race IT's guard instead of "porting" it.

## Finding 3: `published_at` is treated as "will be processed"; nothing re-drives a non-terminal ledger row, and phase 3 dead-letters transient failures
- **Severity:** High
- **Location:** Phase 3, "Requirements" (unexpected exceptions) + "Risk Assessment" (rescheduling transaction fails); Phase 2 "Requirements" (retry-later outcomes); plan.md "Acceptance"
- **Flaw:** After the 200 the only retry engine is "consumer reschedules the outbox row". Every path where the consumer never reaches that statement strands the event forever (ledger `RECEIVED`/`RETRYABLE`, outbox published, provider already told 200). Phase 2 says unexpected errors are rescheduled (up to 12, backoff to 30s); phase 3 says unexpected exceptions get 3 attempts 200ms apart, then DLT. The two contradict, and phase 3's version makes a 0.6s infrastructure blip terminal. The plan lists it as "a limit" with an invariant row, i.e. detection without recovery.
- **Failure scenario:**
  - DB blip: Postgres restart or pool acquisition failure lasting > ~600ms. First transaction fails with a non-lock exception; the "second short transaction" needs the same database and fails too -> error handler -> 3 tries -> DLT -> offset advances. Every record in flight in that window is lost; SUCCESS events never applied, payments expire.
  - Broker loss: single broker, RF 1, `acks=all` is not fsync. `docker compose kill kafka` (or recreating a kafka container with no volume; the plan specifies none) drops records the relay already marked published. Acceptance only exercises a graceful `docker compose stop`.
  - DB reset without broker reset (run.sh tells the user to reset the DB between runs): `auto-offset-reset: earliest`/old offsets replay records whose ledger rows are gone -> each classified "ledger row missing" -> DLT flood.
- **Evidence:**
  - Plan phase 2: "Retry-later outcomes (`PAYMENT_NOT_READY`, `LOCK_TIMEOUT`, `CONCURRENT_UPDATE`, unexpected error): ledger `RETRYABLE`, outbox row rescheduled". Plan phase 3: "Unexpected exceptions: 3 blocking attempts, 200ms apart, then dead-letter" and "The ledger row stays `RECEIVED`/`RETRYABLE` with a published outbox row; list as a limit".
  - Today the same failure is recoverable because the provider redelivers on 500/503: `webhook/WebhookIngestionService.java:86-93`, `:151-162`; `webhook/WebhookEventStore.java:68-82` (`reclaimRetryable`, removed by the plan).
  - Stuck rows are HARD failures already: `load-tests/verify-invariants.sql:38-39`, `:43-44`, `:48-50`.
  - Pool is 10 and saturates (no hikari override in `gateway/src/main/resources/application.yml`; `docs/learning/09-load-test-and-bottlenecks.md:116` "10/10 active, 160 request chờ connection"); the plan adds a relay and two listener threads to it.
  - DB reset instruction: `load-tests/run.sh:16-17`.
- **Suggested fix:** DLT only for provably permanent records (`PoisonRecordException`). For everything else do not advance: unbounded backoff in the error handler (or pause the container). Add one re-drive statement to the relay loop: outbox rows published more than N seconds ago whose ledger row is still `RECEIVED`/`RETRYABLE` get `published_at = null`. Resolve the phase 2 vs phase 3 wording. Add a kill-the-broker case to acceptance and a kafka volume + "reset both" note to run.sh.

## Finding 4: The "webhook path never loses a version check under the row lock" claim holds only because the lookup runs outside the transaction; the joined variant breaks it
- **Severity:** Medium
- **Location:** Phase 3, "Architecture" (first bullet), "Risk Assessment" (JPA flush bullet)
- **Flaw:** The plan removes the `OptimisticRetry` loop from the webhook path citing note 09. That measurement was of code where the first payment read is non-transactional (detached entity) and the locking read inside the transaction is the first load in its persistence context. Phase 3 moves the whole apply into one `TransactionTemplate`; the non-locking lookup and the locking read then share one persistence context.
- **Failure scenario:** Consumer tx: lookup by provider ref loads `Payment@v1 PENDING` into the persistence context (no lock). Cancel commits `v2 CANCELLED`. Consumer runs `findByIdForUpdate`: the row lock is granted on v2, but Hibernate returns the already-managed v1 instance (either unrefreshed, so `transition` passes and the flush `UPDATE ... where version=1` matches 0 rows at commit, after the ledger `markApplied` statement already ran; or the lock-upgrade version check throws at the read). Either way: optimistic failure with the row lock held, transaction doomed. Result: `CONCURRENT_UPDATE`, +1 attempt, 1s backoff, republish, then `ILLEGAL_TRANSITION`, where today it is an immediate `ILLEGAL_TRANSITION`. Eventually correct, but the claim in the plan (and in note 12) is false, the removed retry loop was the thing absorbing it, and phase 5's `received_at -> processed_at` distribution gets a 1s+ tail on exactly the cancel-wins races (542 of 1000 in note 09).
  Related: the existing applier converts lock failures into return values (`retryLater`); a joined variant that keeps doing that hands the processor a doomed transaction (PostgreSQL aborts it on lock timeout; an exception through the repository proxy marks it rollback-only), so "mark ledger" in the same transaction fails with an unrelated error. The plan must say lock failures propagate out of the template as exceptions.
- **Evidence:**
  - Lookup outside any transaction: `webhook/WebhookPaymentApplier.java:74`, `:81`; transaction starts at `:97-101`; service is "Deliberately NOT `@Transactional`" `webhook/WebhookIngestionService.java:22`.
  - Lock failures swallowed into outcomes: `WebhookPaymentApplier.java:103-111`.
  - Code's own statement of why the wrapper exists: `WebhookPaymentApplier.java:39-40` ("The retry wrapper stays: cancel and the sweeper do not take the row lock, so `@Version` is still what catches them").
  - Note 09 claim scope: `docs/learning/09-load-test-and-bottlenecks.md:93`, `:99`. `@Version`: `payment/Payment.java:69`.
- **Suggested fix:** In the joined variant resolve only the payment id without loading the entity (projection query), or take the lock on the first read, or `refresh(p, PESSIMISTIC_WRITE)`. Add an IT: cancel commits between lookup and lock. Keep the lock-held test from step 2 but assert the exception type that leaves the template.

## Finding 5: Reschedule is keyed on `published_at is not null` only; a stale or duplicate record re-arms the row again, and `attempts` has two writers with one cap
- **Severity:** Medium
- **Location:** Phase 2, "Requirements" (retry-later bullet), "Risk Assessment" (reschedule race bullet); Phase 4 "Requirements" (retry bullet)
- **Flaw:** A record carries the outbox id but not which publish it belongs to. Any record of that row, however old, can reschedule the row whenever it happens to be in the published state. "Retried once" is not a mechanism: under READ COMMITTED the conditional `UPDATE` simply blocks on the relay's `FOR UPDATE` lock until the relay batch transaction ends, then re-evaluates; what happens after a second 0-row result is not specified. Separately, `OutboxStore.recordFailure` (relay) and `reschedule` (consumer) both exist and there is one `attempts` column that the 12-cap reads; the plan does not say whether a failed publish increments it.
- **Failure scenario:**
  - Duplicate chain: row R published as r1; relay crashes before the mark (the plan's own crash case) and republishes r2. Consumer lags (2s lock timeout ahead of it, or in phase 4 a 5s merchant timeout). r1 -> not ready -> reschedule (attempts 1). 1s later relay publishes r3 and marks. r2 arrives late -> row is published again -> reschedule succeeds (attempts 2) -> r4. r3 -> reschedule (attempts 3) -> r5. Two chains now live for the life of the retry: attempts burn at 2x, the cap is hit in about half the wall time, and in phase 4 each chain is a real POST to the merchant. "DEAD after 6 attempts" can be reached after ~3 spaced tries, and the IT expecting "attempts = 3, four requests" is nondeterministic.
  - Consumer stall: consumer's reschedule transaction (holding a pooled connection) blocks behind the relay's row lock for the rest of a 100-row batch, or ~5s when the batch ends in a broker timeout.
  - Cap burn by outage (if `recordFailure` increments): the relay stops at the first failure each poll, so the head row takes +1 roughly every 5.2s; a 30s broker outage puts a notification row at the DEAD threshold without one POST, a 60s outage puts a webhook row at 12.
- **Evidence:**
  - Plan phase 2: "outbox row rescheduled (`published_at = null`, `available_at = now() + backoff`, `attempts + 1`)"; "`reschedule` must be a conditional update on `published_at is not null`, retried once"; `OutboxStore` methods "`insert`, `lockDue`, `markPublished`, `recordFailure`, `reschedule`"; outbox columns list a single `attempts`.
  - Plan phase 2 step 5 crash simulation ("publish, then roll back the mark") is exactly the duplicate source. Phase 4 risk bullet expects rebalance redelivery during slow POSTs.
  - Current design has a single-winner arbiter for the equivalent step that the plan deletes: `webhook/WebhookEventStore.java:68-82` (conditional update returning the id, "exactly one gets the id back").
- **Suggested fix:** Put the publish generation in a header (the `attempts` value at publish time) and make reschedule `where id = :id and attempts = :seen and published_at is not null`; 0 rows = stale record, drop silently, state that explicitly. Keep relay publish failures out of `attempts` (separate `publish_failures` or only `last_error`). Add an IT: two records for one row, consumer lag > backoff, assert one reschedule per generation.

## Finding 6: Retry timing lives in database `now()`; tests control only `MutableClock`, and the 12-attempt window outlasts the sweeper's created-timeout
- **Severity:** Medium
- **Location:** Phase 2, "Architecture" (relay SQL bullet), steps 5-6 (cap test); Phase 4 step 4; Phase 5 "Requirements" (settle time)
- **Flaw:** Arithmetic. Webhook backoff 1,2,4,8,16,30x6 = 211s before the 12th attempt. Notification backoff 1+5+15+60+120 = 201s before DEAD. `available_at` is compared with DB `now()`, which no test fixture can move.
- **Failure scenario:**
  - Cap test (`RETRY_EXHAUSTED`) needs 211s of real time, "always 500 -> DEAD" needs 201s, "500 x3 then 200" needs 21s; or the tests rewrite `available_at` by SQL (then they do not test the schedule), or a backoff property is overridden per class (new context, see finding 2). None of the three is in the plan; `fluxpay.outbox.*` keys are unspecified.
  - In a running system `PAYMENT_NOT_READY` can never reach attempt 12: cumulative delay passes 120s at attempt 8, the sweeper fails the `CREATED` row at 120-150s, and the next attempt gets `PAYMENT_NEVER_ACCEPTED`. In tests the sweeper bean does not exist, so the only buildable cap test exercises a branch production cannot take, while the reachable cap cases (`LOCK_TIMEOUT`/`CONCURRENT_UPDATE`/unexpected on a `PENDING` payment -> a SUCCESS event rejected for good after 3.5 min) have no test.
  - Phase 5: settle default is 20s; HARD checks "left retryable", "outbox unpublished", "notifications PENDING" need > 211s/201s after the last request whenever any row was rescheduled. 27+ runs.
- **Evidence:**
  - Plan phase 2: "backoff 1s doubling to 30s; at 12 attempts"; "Relay SQL compares `available_at` with the database `now()`"; phase 4: "backoff 1s, 5s, 15s, 60s, 120s; after 6 attempts -> `DEAD`".
  - `gateway/src/test/java/com/fluxpay/gateway/MutableClock.java:17-46` (application clock only).
  - Sweeper timing: `gateway/src/main/resources/application.yml:63` (created-timeout 2m), `:67` (30s); `payment/PaymentOrchestrator.java:169-172`; sweeper absent in tests `payment/PaymentExpirySweeper.java:21`.
  - Status-dependent outcome: `webhook/WebhookPaymentApplier.java:123-131`.
  - Settle: `load-tests/run.sh:12-13`, `:76-77`; `load-tests/verify-invariants.sql:43-44`.
- **Suggested fix:** Compute `available_at` in Java from the injected `Clock` and pass "now" into `lockDue` as a parameter (tests advance `MutableClock`, no sleeps, no overrides). State in the plan which outcome wins against the sweeper and test the `PENDING`-payment cap case. Derive the run.sh settle default from the configured schedule.

## Finding 7: V9 has no backfill; ledger rows that are `RECEIVED`/`RETRYABLE` at upgrade are acknowledged as duplicates and never processed
- **Severity:** Medium
- **Location:** Phase 2, "Architecture" (V9 bullet), step 4 (remove `reclaimRetryable`), "Success Criteria" (ledger-without-outbox query)
- **Flaw:** The retry owner changes from the provider (503 + reclaim) to the outbox, but rows created under the old contract get no outbox row.
- **Failure scenario:** Dev database kept in its volume. An early webhook leaves event E `RETRYABLE` (503 sent). Gateway is rebuilt on the new branch within the provider's 15.5s retry window, or the row is older and simply still there. Redelivery of E: insert conflicts -> "known event id -> `duplicate_count++`, 200 duplicate". Provider stops. E has no outbox row, nothing will ever process it; payment stays `PENDING` and expires. The phase 2 success query returns non-zero on any non-fresh database.
- **Evidence:**
  - Plan phase 2 requirement: "known event id -> `duplicate_count++`, 200"; V9 description lists only `create table` + index.
  - Today's contract these rows rely on: `webhook/WebhookEventStore.java:68-82`, `:114-121`; `webhook/WebhookIngestionService.java:74-76`, `:127-141`.
  - Retry window: `mock-provider/src/main/resources/application.yml:34-38`.
- **Suggested fix:** In V9: `insert into outbox ... select ... from webhook_events where processing_status in ('RECEIVED','RETRYABLE')`. Or state "fresh database required" in the phase and in README, and scope the success query.

## Finding 8: Relay transaction pins a pooled connection and up to 100 row locks across broker waits, on the one scheduler thread it shares with the sweeper; only one direction of that coupling is assessed
- **Severity:** Medium
- **Location:** Phase 2, "Requirements" (non-functional), "Risk Assessment" (first two bullets)
- **Flaw:** "At most one pooled connection and never while idle" is true but with the broker down the relay is never idle: each run blocks ~5s, then `fixedDelay` 200ms. The plan considers the relay delaying the sweeper, not the sweeper (or a long full-batch loop) delaying the relay, and not what else waits on the locked rows.
- **Failure scenario:**
  - Outage under load (phase 5 step 5): relay holds 1 of 10 connections ~96% of the time in a pool that already queues 160 requests; webhook accept (now the only thing between the provider and a lost event) competes for the remaining 9.
  - Sweep with many rows: each row is its own transaction on the scheduler thread, and from phase 4 each writes two more rows. A sweep over a few thousand overdue rows stalls publishing for its whole duration; prediction 2 ("one relay interval plus consumer time") is silently wrong during sweeps.
  - "Loops again at once when a batch was full": while a backlog drains the sweeper does not run at all.
  - `max.block.ms` only bounds metadata/buffer waits; with metadata cached, the bound is `delivery.timeout.ms`, which cannot go below `request.timeout.ms` (30s default) + linger. The plan says "verify"; if it is missed the per-poll hold is 30s+, not 5s.
- **Evidence:**
  - Plan phase 2: "tx { select due ... for update skip locked ; send + wait ack ; mark published_at }"; "a blocked relay delays a sweep by at most the producer timeout. Accept".
  - One scheduler thread: no `spring.task.scheduling` in `gateway/src/main/resources/application.yml` (grep `task|pool` empty); `payment/SchedulingConfig.java:12-15`.
  - Sweeper work on that thread: `payment/PaymentExpirySweeper.java:34-43`; per-row transactions `payment/PaymentOrchestrator.java:174-187`.
  - Pool saturation: `docs/learning/09-load-test-and-bottlenecks.md:116`.
- **Suggested fix:** Claim-then-send: short tx to claim a batch (`claimed_until`), send with no transaction open, short tx to mark. This also removes the consumer-blocks-on-relay-lock path in finding 5. Give the relay its own single-thread executor. Set `request.timeout.ms` explicitly alongside `delivery.timeout.ms` in the plan.

---

## Unresolved questions
1. Hibernate (Boot 4.1.2 line) behaviour when a `PESSIMISTIC_WRITE` query returns an entity already managed at an older version: stale instance or immediate version-check exception? Finding 4 holds either way, the fix differs. Needs a 10-line IT.
2. Does `recordFailure` increment `attempts`? (finding 5)
3. Is a notification wanted for `CREATED -> FAILED` (create answered 502, sweeper `CREATE_ABANDONED`)? The plan's transition list includes it implicitly; product call.
4. Kafka data volume in compose: none specified. Intended?
