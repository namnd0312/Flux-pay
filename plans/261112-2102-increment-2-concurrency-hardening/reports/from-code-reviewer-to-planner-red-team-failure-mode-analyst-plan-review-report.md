# Red-team plan review: Failure Mode Analyst

Plan: `plans/261112-2102-increment-2-concurrency-hardening/` (plan.md + phase 1-4). Reviewed 2026-11-12.
Method: read all plan files + traced planned flows against real code. No build/test run. Paths below relative to repo root; `GW` = `gateway/src/main/java/com/fluxpay/gateway`, `MP` = `mock-provider/src/main/java/com/fluxpay/mockprovider`.

Verdict: 1 Critical, 3 High, 6 Medium. Findings 1-3 break invariants the plan states as acceptance criteria.

---

## Finding 1: Early-webhook fix has a TOCTOU hole: two unsynchronized reads re-create the exact loss it fixes
- **Severity:** Critical
- **Location:** Phase 2, section "Architecture" (`validateAndApply` pseudo-code, lines 55-58)
- **Flaw:** Fallback does read #1 `findByProviderCodeAndProviderRef` -> empty, then read #2 `findById(merchantRef)`; only `status == CREATED` is retryable, `else -> REJECTED(UNKNOWN_PAYMENT)`. The two reads run outside any tx (separate snapshots). Create tx2 can commit between them.
- **Failure scenario:**
  1. create: tx1 commits CREATED; provider call returns; tx2 about to commit.
  2. webhook: read #1 by `(providerCode, providerRef)` -> empty (tx2 not committed).
  3. create tx2 commits: `provider_ref` set, status PENDING.
  4. webhook: read #2 by id -> found, status PENDING (not CREATED) -> `REJECTED(UNKNOWN_PAYMENT)`, HTTP 200.
  5. Provider acked, dedup row burned, redelivery = duplicate. Payment PENDING -> EXPIRED. Money taken. Same outcome as today, narrower window.
  The window sits exactly where `auto-pay` + `create.delay-ms` puts the webhook (retry attempt lands near tx2 commit). `EarlyWebhookIT` uses a latch, so it can never hit this interleaving: test green, bug live.
- **Evidence:** `GW/webhook/WebhookIngestionService.java:112-115` (lookup outside tx, empty -> `UNKNOWN_PAYMENT`); `GW/payment/PaymentOrchestrator.java:107-114` (tx2 sets ref + PENDING in one commit); plan phase 2 lines 55-58 quote: `found && status == CREATED -> RETRYABLE(PAYMENT_NOT_READY)` / `else -> REJECTED(UNKNOWN_PAYMENT)`.
- **Suggested fix:** One-row decision. In fallback, after `findById`: CREATED -> RETRYABLE; `providerRef` equals event ref (any status) -> continue normal path with that row; else reject. Or simply: fallback found and not CREATED -> RETRYABLE once (redelivery then resolves by ref). Add an IT that commits tx2 between the two reads (latch in a test repository spy or reorder reads).

## Finding 2: `lease_epoch` is not monotonic: `abandon` DELETEs the row, next `claim` restarts at 1 (ABA defeats the fence)
- **Severity:** High
- **Location:** Phase 3, sections "Requirements" + "Architecture"; plan.md "Key decisions" ("`lease_epoch` column (monotonic)")
- **Flaw:** Epoch lives in the row; `deleteOwned` removes the row; `claim` inserts `lease_epoch = 1` again. A zombie holding epoch 1 matches a later generation's epoch 1. TTL purge (deletes any status) has the same effect.
- **Failure scenario (deterministic with `MutableClock`):**
  1. A `begin` -> NEW, epoch 1. A stalls.
  2. +61s. B `begin` -> take-over, epoch 2.
  3. B's response is non-storable (500, or the new phase-1 503) -> `abandon(epoch 2)` -> row deleted.
  4. Merchant retries: C `begin` -> `claim` inserts fresh row, epoch **1**, IN_PROGRESS.
  5. Zombie A wakes: `complete(m,k,1,...)` -> `lease_epoch = 1 AND status = 'IN_PROGRESS'` matches C's live lease -> A's stale response stored; or A `abandon(1)` deletes C's live row -> D runs concurrently with C.
  Acceptance "Zombie idempotency writes affect 0 rows" is false.
- **Evidence:** `GW/idempotency/IdempotencyStore.java:113-118` (`delete`), `:121-125` (`deleteExpired` ignores status), `:38-56` (`claim` = plain insert); `GW/idempotency/IdempotencyFilter.java:100-104` (abandon on every non-storable status), `:45` (500/503 not storable); plan phase 3 line 28 "`claim` sets `lease_epoch = 1`", line 44 `deleteOwned`. Plan risk only mentions "TTL purge racing an in-flight request ... ignored"; delete-then-reclaim not considered.
- **Suggested fix:** Make the token unique across row generations: `lease_epoch BIGINT NOT NULL DEFAULT nextval('idempotency_lease_epoch_seq')`, take-over sets `nextval(...)` (still one statement, still monotonic). Alternative: abandon = expire lease in place (keeps row + epoch), but that changes "retry with different body" from NEW to 422. Add the 5-step scenario above to `LeaseFencingIT`.

## Finding 3: Duplicate of an in-flight (`RECEIVED`) event is acked 200 while the owner later ends `RETRYABLE`; and the INTERNAL_ERROR path cannot mark what it promises
- **Severity:** High
- **Location:** Phase 2, sections "Requirements" (line 34: "Redelivery of `APPLIED`/`REJECTED`/`RECEIVED` stays a duplicate (200)"; line 36: "No event may stay `RECEIVED` after a handled failure") and "Architecture" (tx A / step 3 / tx C)
- **Flaw:** (a) Outcome of a RECEIVED row is unknown, yet a concurrent delivery is told 200 = "done". (b) `markRetryable` is only attempted for step-3 exceptions; the most likely `RuntimeException` (pool exhausted / DB blip -> `CannotCreateTransactionException`) also kills the `markRetryable` tx and tx C. Row stays RECEIVED, response 500, provider redelivers -> duplicate 200 -> lost.
- **Failure scenario (a):**
  1. D1: tx A inserts RECEIVED. D1 slow (Tomcat threads parked on slow provider in R4, lock wait, pool wait).
  2. Provider read timeout fires (mock: 5s) -> resend D2 (planned retry on IO error).
  3. D2: `insertNew` empty, `reclaimRetryable` 0 rows (status RECEIVED) -> 200 duplicate. Provider stops retrying.
  4. D1 finishes `markRetryable` (PAYMENT_NOT_READY / LOCK_TIMEOUT / INTERNAL_ERROR) -> 503 to a closed connection.
  5. Event RETRYABLE forever, nobody redelivers. Paid order never SUCCEEDED.
  Same with the plan's own test shape: two concurrent redeliveries of a RETRYABLE event while payment is still CREATED -> one gets 200, the other 503 -> provider that treats any 2xx as ack stops.
- **Failure scenario (b):** tx B commits SUCCEEDED, tx C `markApplied` fails -> not covered by the step-3 catch -> event RECEIVED forever (payment correct, ledger wrong, phase-4 invariant 4 trips). Or step 3 fails on connection acquisition -> `markRetryable` fails the same way -> RECEIVED + 500 -> redelivery = 200 duplicate -> lost.
- **Evidence:** `GW/webhook/WebhookIngestionService.java:71-78` (duplicate decided purely on insert conflict), `:86-93` (tx C separate, unguarded); `GW/webhook/WebhookEventStore.java:62-79` (mark* are `where id = :id`, no status guard); `mock-provider/src/main/resources/application.yml:7-8` (read-timeout 5s); `gateway/src/main/resources/application.yml` has no hikari/tomcat settings (grep `hikari|tomcat` -> none; defaults: 30s connection wait).
- **Suggested fix:** Duplicate response depends on row status: APPLIED/REJECTED -> 200; RECEIVED (in flight) -> 503 retry-later, no reclaim. Costs one `returning processing_status` on `incrementDuplicate`. Then a failed `markRetryable` is self-healing only if stale RECEIVED is also reclaimable (`received_at < now - N s`); if that is too much for this increment, state it under known limits as "DB error after insert = lost event", not as "handled".

## Finding 4: mock-provider retry loop runs inside a 2-4 thread executor with a 50-slot queue and abort-on-full; retries everything non-2xx
- **Severity:** High
- **Location:** Phase 2, sections "Requirements" (line 39) + mock-provider knobs table; Phase 4 run matrix R3/R4 and Risk ("use `MOCK_CALLBACK_DELAY_MS` to line the webhook up")
- **Flaw:** Backoff sleeps + per-attempt 5s read timeout happen on the `webhookExecutor` thread. Worst case one event pins a thread 5x5s + 3s = 28s. Pool: core 2, max 4 (only after queue is full), queue 50, default `AbortPolicy`. On rejection `sender.send` throws `TaskRejectedException` at the call site -> `/mock/v1/pay` returns 500 and the webhook is never sent, never retried. "non-2xx" also retries 401/404/413 (never succeed).
- **Failure scenario:** R4 (`MOCK_CREATE_DELAY_MS=2000`, 200 threads): gateway Tomcat threads parked on create; webhooks queue behind them; mock sends time out at 5s and retry (amplifies load 5x); 2 sender threads stuck ~28s each; queue fills at 50; further pays get 500 with no webhook. Payments stay PENDING. Note 09 then names "the first bottleneck" of the gateway while the real limiter is the mock's sender pool, which `collect-metrics.sh` never samples (gateway + Postgres only). Same collapse with `MOCK_CALLBACK_DELAY_MS` (sleep on the same threads: 100 ms delay = max 20-40 webhooks/s).
  Secondary: default retry window 200+400+800+1600 = 3.0s is shorter than the provider latency the gateway itself tolerates (connect 2s + read 5s). Create delay 3-5s -> all 5 attempts spent before tx2 commits -> RETRYABLE forever by default config, not by exotic failure.
- **Evidence:** `MP/config/AsyncConfig.java:24-26` (2/4/50, no rejection handler; grep `setRejectedExecutionHandler|TaskRejected` -> none); `MP/webhook/WebhookSender.java:48,55-57,59-72` (@Async, in-thread sleep, sequential loop); `MP/pay/PayController.java:37` (send call unguarded); `gateway/src/main/resources/application.yml:7-8`; plan phase 2 line 39 "retries a webhook on non-2xx or IO error", lines 86-87 defaults.
- **Suggested fix:** Retry only on 5xx / 429 / IO error; stop on other 4xx. Size the retry window above 7s (e.g. 6 attempts from 250 ms, or cap + more attempts). Raise pool/queue for load runs or use `CallerRunsPolicy`, and log + count rejected sends. Phase 4: sample mock executor queue depth / rejected count and add "paid at mock but not terminal at gateway" as an explicit check. State how `duplicates` composes with retry (each copy retried? copies stop on 503?).

## Finding 5: Phase 4 invariant SQL is vacuous where it matters and blind to the money-loss outcomes
- **Severity:** Medium
- **Location:** Phase 4, section "Requirements" (`verify-invariants.sql` list) + step 7 + Success Criteria
- **Flaw:**
  - "`webhook_events` in RECEIVED or RETRYABLE older than 1 minute" run by `run.sh` right after the scenario: a short run (same-key-burst, 50-thread webhook-vs-cancel) has no row older than 1 minute -> always 0 rows. R0's question "how many stuck events before the fixes" is answered 0 on the broken build. Events stuck in the last 60s of any run are never seen.
  - "`idempotency_keys` IN_PROGRESS with `lease_until` older than 1 minute": `lease_until = claim + 60s`, so the row must be over 2 minutes old. Same vacuity.
  - RETRYABLE rows are legitimate by the plan's own known limit (attempts exhausted): the check either false-positives or is vacuous; never informative.
  - "more than one live payment per (merchant_id, order_id)" cannot fail: unique index enforces it.
  - No check for the actual loss shapes: `webhook_events` REJECTED (`UNKNOWN_PAYMENT`/`ILLEGAL_TRANSITION`) carrying `status=SUCCESS` for a payment that is not SUCCEEDED; payments PENDING after a mock pay. Findings 1, 3, 4 all pass the current list.
  - "`payments.status` differs from latest transition": CREATED rows have no transition row at all; a LEFT JOIN version false-positives on any in-flight create.
- **Evidence:** plan phase 4 lines 38-43, line 89, line 107; `GW/idempotency/IdempotencyService.java:47` (`now.plus(lease)`); `gateway/src/main/resources/application.yml:47` (lease 60s); `gateway/src/main/resources/db/migration/V7__add_live_order_unique_index.sql:10-12`; `GW/payment/PaymentOrchestrator.java:88` (tx1 saves payment only; `transitions.save` appears only in `PaymentStateMachine.java:57`).
- **Suggested fix:** Quiesce then check without age filter: wait > mock retry window + lease (or just stop load, sleep 10s, check events; check keys with `lease_until < now()`). Add: baseline scenario -> 0 REJECTED events and 0 non-terminal payments that were paid; webhook-vs-cancel -> every REJECTED event's payment is CANCELLED. Report RETRYABLE count as a number, not a pass/fail invariant.

## Finding 6: Paid-but-FAILED path is mis-described; the rejected event loses its payment link
- **Severity:** Medium
- **Location:** Phase 2, section "Risk Assessment" (line 131) vs "Architecture" (lines 55-58); plan.md "Known limits"
- **Flaw:** Risk says after create dies / sweeper fails the CREATED row "a later redelivery is `ILLEGAL_TRANSITION`". Per the planned code it is not: `provider_ref` was never stored -> lookup by ref empty -> fallback finds status FAILED (not CREATED) -> `REJECTED(UNKNOWN_PAYMENT)` with `payment_id = null`. The state machine is never consulted. The reconciliation increment the plan defers to gets a row indistinguishable from garbage.
- **Failure scenario (reachable without a crash):** mock `create.delay-ms` above the gateway read timeout (or response lost): session exists at provider, `auto-pay` fires SUCCESS early -> 503 RETRYABLE. Gateway times out -> `compensateToFailed` -> 502, which IS storable and replayed for 24h. Redelivery -> `UNKNOWN_PAYMENT`, 200, `payment_id` null. Customer charged, payment FAILED, merchant told "provider unavailable"; FAILED is not live (V7), so a retry with a new key creates a second payment for the same order -> second charge.
  Related, not in "Known limits": FAILED-then-SUCCESS on one session is accepted by the mock (`PayController` has no state check) and ends "money taken, payment FAILED, order re-creatable". Phase 2 documents it as correct ordering behaviour only.
- **Evidence:** `GW/payment/PaymentOrchestrator.java:98-100` (compensate on provider failure), `:109-111` (ref stored only in tx2); `GW/idempotency/IdempotencyFilter.java:45` (502 storable); `GW/webhook/WebhookIngestionService.java:113-115` (`Validation(null, "UNKNOWN_PAYMENT")`); `MP/pay/PayController.java:35-37`; V7 lines 10-12.
- **Suggested fix:** Fallback found-but-not-CREATED -> distinct reason (e.g. `PAYMENT_NOT_PAYABLE`) and store `payment_id`. Fix the risk text. List both "paid but FAILED" shapes under known limits with the query that finds them. No behaviour change to first-terminal-wins requested.

## Finding 7: Phase 1 "503 releases the key" invites a retry that is deterministically poisoned; tx2 failures leave an orphan live row
- **Severity:** Medium
- **Location:** Phase 1, sections "Requirements" (retry exhaustion -> 503 + `Retry-After: 1`) and "Risk Assessment" (tx2 vs `failAbandonedCreated`)
- **Flaw:** Create tx2 is not inside any try/compensate. If it ends in 503 (exhaustion) or any 500, the payment stays CREATED = live. The key is dropped, the merchant obeys `Retry-After: 1`, tx1 of the retry violates `ux_payments_live_order` -> `409 ORDER_ALREADY_EXISTS`, which is storable -> replayed for 24h. So "not stored, key released" is true but the advertised retry cannot succeed for 2 minutes (until the sweeper), and its failure is then frozen under the key. The provider session is orphaned either way. For the acknowledged `IllegalPaymentTransitionException` in tx2: `409 PAYMENT_ILLEGAL_TRANSITION` is stored for a create call, and a later webhook for that session goes down Finding 6's `UNKNOWN_PAYMENT` path.
  Also: the new `@ExceptionHandler(OptimisticLockingFailureException)` is global; on the webhook path it turns exhaustion into `503 CONCURRENT_UPDATE` while phase 2 says unexpected errors answer 500. Pick one.
- **Evidence:** `GW/payment/PaymentOrchestrator.java:107-114` (tx2 outside the try at `:95-105`), `:88-91`; V7 lines 10-12; `GW/idempotency/IdempotencyFilter.java:45,95-104`; `GW/common/ProblemDetailsHandler.java:40-43,46-49`; `gateway/src/main/resources/application.yml:59` (created-timeout 2m).
- **Suggested fix:** Since the plan proves exhaustion unreachable on create, do not advertise a retry there: either compensate CREATED -> FAILED when tx2 throws a non-business exception (then 503 + retry really works), or drop `Retry-After` on the create path and document "retry after created-timeout". Decide the webhook exhaustion status explicitly.

## Finding 8: Reclaimed event rows keep stale columns; mark* writes are unguarded; redelivered bytes are not the stored bytes
- **Severity:** Medium
- **Location:** Phase 2, section "Architecture" (`WebhookEventStore` additions)
- **Flaw:** `reclaimRetryable` sets only `processing_status`. `markApplied` does not touch `reject_reason`. An early webhook that later applies ends as `APPLIED` with `reject_reason = 'PAYMENT_NOT_READY'` and an old `processed_at` in between. Any report or invariant keyed on `reject_reason is null` miscounts. Reclaim does not bump `duplicate_count`, so delivery attempts are not countable. Reclaim matches `(provider_code, event_id)` only: the redelivery's payload is processed but `raw_body` keeps the first delivery's bytes; "same bytes" is a requirement nothing enforces, and V4 promises the stored body is the audited one.
- **Evidence:** `GW/webhook/WebhookEventStore.java:62-69` (`markApplied` columns), `:71-79`; plan phase 2 lines 69-73; `gateway/src/main/resources/db/migration/V4__create_webhook_events.sql:4` ("raw_body is stored verbatim so the exact signed bytes are auditable").
- **Suggested fix:** Reclaim: `set processing_status='RECEIVED', reject_reason=null, processed_at=null, duplicate_count = duplicate_count + 1 ... and raw_body = :raw` (mismatch -> treat as duplicate/reject). `markApplied` sets `reject_reason = null`. Add `and processing_status = 'RECEIVED'` to the three mark* updates.

## Finding 9: Pessimistic arm: `SET LOCAL` cannot take a bind parameter; the lock IT has no deterministic "is blocked" signal
- **Severity:** Medium
- **Location:** Phase 2, "Key Insights" (line 29), "Non-functional" (`fluxpay.webhook.lock-timeout: 2s`), step 9
- **Flaw:**
  - PostgreSQL rejects `SET LOCAL lock_timeout = :v` (utility statement, no parameters). With a configurable `Duration` the natural `JdbcClient.param` code fails at runtime with a syntax error -> every pessimistic webhook becomes RETRYABLE(INTERNAL_ERROR) + 500 and the mock retries 5x. Not listed among the [UNVERIFIED] items.
  - `WebhookPessimisticLockIT` (a) "webhook thread blocks, release -> applied": phase 1 demands "no sleeps, latches only", but a JDBC lock wait exposes no latch. Without polling `pg_stat_activity` the test either sleeps or releases before the webhook thread reaches the SELECT (passes without ever blocking = phantom test). With the 2s default timeout a slow CI run flips it to 503.
  - (b) needs `lock-timeout: 200ms` via `@TestPropertySource` -> extra Spring context; `CountingFakeProvider` and the planned latch are per-context beans, the Postgres container is shared: latch/reset discipline is unspecified.
  - Lock-timeout exception class is assumed (`PessimisticLockingFailureException`); after 55P03 the Postgres tx is aborted, so nothing else may run in tx B's catch.
- **Evidence:** `GW/webhook/WebhookEventStore.java:25-29` (JdbcClient, named params only); `gateway/src/test/java/com/fluxpay/gateway/AbstractIntegrationTest.java:35-52` (shared static container, context-scoped test beans); `gateway/src/test/java/com/fluxpay/gateway/CountingFakeProvider.java:82-85` (`reset()` knows nothing of a latch); research `researcher-01-locking-and-retry-report.md:63-64` ("Verify in test").
- **Suggested fix:** `select set_config('lock_timeout', :ms || 'ms', true)`. In the IT wait on `select 1 from pg_stat_activity where wait_event_type = 'Lock'` (bounded poll) before releasing. Pin the exception class in the same step that pins the optimistic one.

## Finding 10: Missed consumers and environment gaps (Contract Verifier)
- **Severity:** Medium
- **Location:** Phase 2 "Related Code Files"; Phase 4 steps 5 and 8
- **Flaw / evidence:**
  - `SessionController` gains `WebhookSender` + `MockProperties` (auto-pay, create delay). `mock-provider/src/test/java/com/fluxpay/mockprovider/session/SessionControllerTest.java:18-19` is `@WebMvcTest(SessionController.class) @Import(PaymentSessionStore.class)`: slice has neither bean -> context fails. Not in the modify list (only `WebhookSenderTest`).
  - `MockProperties` record shape change breaks `mock-provider/src/test/.../webhook/WebhookSenderTest.java:34-35` (listed, OK) and the existing 3-send expectation at `:39` must be re-specified against retry semantics.
  - "Always 200" contract is written in 4 places the plan does not list: `docs/learning/03-webhook-ipn.md:16,30`, `GW/webhook/WebhookIngestionService.java:35-36`, `GW/webhook/WebhookController.java:14-15,40`, `GW/webhook/WebhookResult.java:4-6`. Phase 4 updates README/architecture only.
  - Phase 1 step 3 "reuse the webhook signing used by `WebhookIngestionIT`": it is a private helper (`WebhookIngestionIT.java:86-93`), needs extraction to `AbstractIntegrationTest`.
  - R0 worktree: `.env` is git-ignored (`.gitignore:9`) and compose hard-requires `FLUXPAY_SECRET_KEY` (`docker-compose.yml:34`) -> old stack will not start as written; host ports are fixed (`docker-compose.yml:12,36,52`) -> current stack must be down; `MOCK_CALLBACK_DELAY_MS` is not passed through in the old compose (`docker-compose.yml:47-50`), so the suggested alignment knob needs the override file applied to the old tree too.
  - Phase 4 step 5 "two parallel samplers behind a Synchronizing Timer": samplers inside one JMeter thread run sequentially; the timer aligns different threads on the same sampler, and those threads do not share a payment id. Stock JMeter has no parallel controller (plugin). Research never read that doc section (`researcher-02-...:132,169,175`). Simplest working shape: single thread, pay (202 at once) with `MOCK_CALLBACK_DELAY_MS = D`, timer ~D, cancel.
- **Suggested fix:** Add the listed files to the phase 2 modify list; add an R0 checklist (copy `.env`, `docker compose down` first, `-f` override); redesign the race scenario before authoring the .jmx.

---

## Fact Checker (sample)

Phase 1
- `@Version` on `Payment.version`: VERIFIED `GW/payment/Payment.java:69-70`
- Every transition lambda re-reads inside its tx: VERIFIED `PaymentOrchestrator.java:108,119,137,180`; `WebhookIngestionService.java:127`
- No optimistic-lock handler, falls to 500: VERIFIED `ProblemDetailsHandler.java:91-94` (catch-all also logs nothing: cancel/webhook 500s have no stack trace anywhere)
- `version` changes only with a transition: VERIFIED (setters called only at `PaymentOrchestrator.java:109-111`, `touch` only at `PaymentStateMachine.java:56`)
- 503 not in `STORABLE_STATUSES`: VERIFIED `IdempotencyFilter.java:45`
- Sweeper REQUIRES_NEW + per-row catch: VERIFIED `PaymentOrchestrator.java:70,167-174`
- Provider timeouts 2s + 5s, created-timeout 2m: VERIFIED `gateway/.../application.yml:7-8,59`
- 39 IT: VERIFIED (count of `@Test` in `*IT.java`); 62 unit: UNVERIFIED (journal claim only, `docs/journals/2026-11-07-...md:10`)
- Micrometer available via actuator: VERIFIED `gateway/pom.xml:45`; no `MeterRegistry` use exists yet
- Demo A "T1 loads payment (version 0)": FAILED for a PENDING payment (tx2 already bumped it to 1); cosmetic
- Tag `increment-1-done`: not present yet (`git tag` empty); planned in step 1

Phase 2
- `processing_status VARCHAR(20)`, no CHECK: VERIFIED `V4__create_webhook_events.sql:12`; reasons fit `reject_reason VARCHAR(40)` `:13`
- Early webhook -> `UNKNOWN_PAYMENT` + burned dedup: VERIFIED `WebhookIngestionService.java:72-77,112-115`
- `merchantRef` = payment id: VERIFIED `PaymentOrchestrator.java:97`, `MockProviderAdapter.java:63`
- CREATED -> {PENDING, FAILED} only: VERIFIED `PaymentStateMachine.java:26`
- `Callback(delayMs, duplicates)`, env knobs: VERIFIED `MP/config/MockProperties.java:18`, `mock-provider/.../application.yml:28-29`
- Sender does not retry today: VERIFIED `WebhookSender.java:69-71`
- `loadForSweep` exists: VERIFIED `PaymentRepository.java:31`
- "later redelivery is `ILLEGAL_TRANSITION`" (risk line 131): FAILED, see Finding 6
- JdbcClient shares JPA tx connection; `FOR UPDATE` vs `FOR NO KEY UPDATE`: UNVERIFIED (plan flags both)
- `docker-compose.yml` lacks mock knob passthrough: VERIFIED `:47-50` (plan lists the modify)

Phase 3
- `complete`/`delete` have no owner check: VERIFIED `IdempotencyStore.java:99-118`
- Caller line refs (`IdempotencyFilter.java:77,97,103`; `IdempotencyServiceIT.java:59`; `IdempotencyService.java:47,68,76,81`; `IdempotencyIT.java:97`): all VERIFIED
- `NEW` is a shared constant: VERIFIED `IdempotencyOutcome.java:17`
- lease 60s / ttl 24h: VERIFIED `application.yml:45,47`
- No JPA entity for the table: VERIFIED `V6__create_idempotency_keys.sql:9-10`
- V8 is the next migration: VERIFIED (V1-V7 present)
- `ux_payments_live_order` backstop: VERIFIED `V7:10-12`
- "`lease_epoch` (monotonic)": FAILED as designed, see Finding 2
- No other callers of `store.delete` / `abandon` / `complete` in tests: VERIFIED by grep

Phase 4
- Provider call outside tx: VERIFIED `PaymentOrchestrator.java:94-97`
- Canonical string shape: VERIFIED `GW/common/CanonicalRequest.java:35-39`
- Actuator exposes `health` only: VERIFIED `application.yml:28-32`
- `scripts/burst-replay.sh`, roadmap report, `/opt/homebrew/bin/jmeter`: VERIFIED
- `load-tests/` absent, to be created: VERIFIED
- Tomcat metric names, JMeter flags: UNVERIFIED (plan flags)

## Contract Verifier

| Change | Consumers (file:line) | Covered by plan |
|---|---|---|
| `IdempotencyService.complete/abandon` + epoch | `IdempotencyFilter.java:97,103`; `IdempotencyServiceIT.java:59` | yes |
| `IdempotencyOutcome` new component / `ofNew(epoch)` | `IdempotencyService.java:48,69`; record ctor uses `IdempotencyOutcome.java:17-19,34` | yes (implicit) |
| `IdempotencyStore.claim/takeOver` return type | `IdempotencyService.java:47,68` | yes |
| `WebhookResult` + `retryable` | `WebhookController.java:36-37`; factories `WebhookResult.java:10-20` | yes |
| `WebhookController` -> `ResponseEntity`, 503 | `WebhookIngestionIT.java:110-184` (200 asserts stay valid); docs/Javadoc in Finding 10 | partly: docs missed |
| `MockProperties` shape | `WebhookSenderTest.java:34-35`; `WebhookSender.java:55-58` | yes |
| `SessionController` deps | `SessionControllerTest.java:18-19` | **missed** |
| `WebhookSender` retry semantics | `PayController.java:37`; `WebhookSenderTest.java:39` | partly: `duplicates` composition unspecified |
| Global `OptimisticLockingFailureException` -> 503 | all controllers incl. `WebhookController` | conflict with phase 2 "500" (Finding 7) |
| Webhook HTTP contract 200 -> 200/503/500 | mock sender only (`WebhookSender.java:61-71`); Postman collection (not inspected) | yes |

## Unresolved questions
1. Is a 2xx from any concurrent copy an ack for the mock's retry loop (it is sequential today, so only timeouts create overlap)? Decides how hard Finding 3(a) bites in this repo vs. only with a real provider.
2. Should webhook retry exhaustion answer 500 or 503? Plan says both (phase 1 handler vs phase 2 requirement).
3. Does the user accept "paid but FAILED" (Finding 6) as a documented limit until reconciliation, and should the merchant be able to re-create that order meanwhile?
4. Finding 2 fix choice: sequence-backed epoch vs expire-in-place abandon (the latter changes 422 behaviour). User decision.
