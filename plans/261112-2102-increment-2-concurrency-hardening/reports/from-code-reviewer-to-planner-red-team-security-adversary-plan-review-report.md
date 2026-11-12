# Red-team plan review: Security Adversary lens

Plan: `plans/261112-2102-increment-2-concurrency-hardening/` (plan.md + phases 1-4). Reviewed 2026-11-12.
Method: read all plan files, grep/read cited code. No build, lint or tests run. Paths relative to repo root `/Users/admin/Desktop/DEV/AI/Flux-pay`.

Severity count: 5 High, 3 Medium. No Critical.

---

## Finding 1: `lease_epoch` restarts at 1 after abandon-delete, zombie passes the fence (ABA)
- **Severity:** High
- **Location:** Phase 3, sections "Requirements" + "Architecture"; plan.md "Key decisions" ("`lease_epoch` column (monotonic)")
- **Flaw:** Epoch lives in the row. `abandon` deletes the row, next `claim` inserts `lease_epoch = 1` again. Epoch is not monotonic per key, so `lease_epoch = :mine AND status = 'IN_PROGRESS'` matches a stranger's lease.
- **Failure scenario:** A claims (epoch 1), stalls > 60s. B takes over (epoch 2), gets 500/503 (non-storable; phase 1 adds `503 CONCURRENT_UPDATE`) -> `deleteOwned` removes row. C retries same key -> fresh `claim`, epoch 1, IN_PROGRESS. A wakes: `complete(m,k,epoch=1,...)` matches C's row -> A's stale response stored over C's live lease; or A's `abandon` deletes C's row -> 4th request runs. Exactly the two bugs the phase claims to close; phase success criterion "Zombie complete and abandon affect 0 rows" is false. `LeaseFencingIT` as specified (A, B only) will not catch it.
- **Evidence:** `IdempotencyStore.java:113-118` (delete by key), `IdempotencyFilter.java:45` (500/503 not storable), `:100-104` (abandon on non-storable), `IdempotencyStore.java:40-45` (claim = plain insert). Plan: "`claim` sets `lease_epoch = 1`", "`deleteOwned(m, k, epoch)` ... (replaces delete)". Research `researcher-02-...:26` "insert with 1"; `:30` ranks owner UUID as alternative.
- **Suggested fix:** Make token unique across row lifetimes: `lease_epoch` from a sequence (`nextval`) on both claim and take-over, or a random owner UUID (research option). Add IT: A claim -> expire -> B take-over -> B abandon -> C claim -> A complete/abandon must affect 0 rows.

## Finding 2: Early-webhook fallback is check-then-act across two unlocked reads; the loss it fixes still happens
- **Severity:** High
- **Location:** Phase 2, section "Architecture" (`validateAndApply` pseudo-flow)
- **Flaw:** Flow = read 1 `findBy(providerCode, providerRef)` -> empty -> read 2 `findById(merchantRef)` -> `status == CREATED` ? RETRYABLE : `REJECTED(UNKNOWN_PAYMENT)`. No transaction spans the two reads. If create tx2 commits between them, read 2 sees PENDING -> permanent reject, 200, dedup row burned.
- **Failure scenario:** Webhook (or one of the 5 mock retries) lands as tx2 commits: read 1 empty, tx2 commits, read 2 = PENDING -> `REJECTED UNKNOWN_PAYMENT` + HTTP 200 -> provider stops -> paid order stays PENDING then EXPIRED. Phase 2/4 knobs (`auto-pay` + `create.delay-ms` + backoff retries) aim deliveries at exactly that instant. Breaks acceptance "Early webhook ends SUCCEEDED after provider redelivery".
- **Evidence:** `WebhookIngestionService.java:111-115` (validation reads run outside any tx; class deliberately not `@Transactional`, `:24`), `application.yml:18` (`open-in-view: false`), `PaymentOrchestrator.java:107-114` (providerRef + PENDING committed together in tx2). Plan: "found && status == CREATED -> RETRYABLE(PAYMENT_NOT_READY); else -> REJECTED(UNKNOWN_PAYMENT)".
- **Suggested fix:** In the fallback, if payment found and no longer CREATED, re-run the providerRef lookup once (or resolve by `merchantRef` id first, then compare `providerRef`). Reject permanently only when the id truly does not exist or the committed `provider_ref` differs. Add an IT with the latch released between the two reads.

## Finding 3: Redelivery of an in-flight `RECEIVED` event is acked 200, cancelling the provider's retry
- **Severity:** High
- **Location:** Phase 2, section "Requirements" ("Redelivery of `APPLIED`/`REJECTED`/`RECEIVED` stays a duplicate (200)")
- **Flaw:** 200 means "done, stop retrying". For `RECEIVED` the first delivery has not finished and may still end `RETRYABLE`. Plan adds a retrying sender, so overlap is now possible.
- **Failure scenario:** Gateway slow under load (Hikari default pool, 3 transactions per webhook, lock wait up to 2s). Mock times out at 5s (IO error) -> resends same eventId while attempt 1 still in flight -> row `RECEIVED` -> 200 duplicate -> mock stops. Attempt 1 then ends `RETRYABLE` (`LOCK_TIMEOUT` / `PAYMENT_NOT_READY` / `INTERNAL_ERROR`). Nobody redelivers: event stuck, payment never SUCCEEDED. Also fails phase 4 invariant "`webhook_events` in `RECEIVED` or `RETRYABLE` older than 1 minute = 0 rows".
- **Evidence:** `WebhookIngestionService.java:71-78` (duplicate -> 200 regardless of row status), `mock-provider/src/main/resources/application.yml:7-8` (read-timeout 5s), `gateway/src/main/resources/application.yml` (no hikari/tomcat settings -> defaults). Plan table: "resend on non-2xx / IO error, same eventId + bytes".
- **Suggested fix:** Duplicate of `RECEIVED` -> 503 `WEBHOOK_RETRY_LATER` (still in progress); 200 only for `APPLIED`/`REJECTED`. Optionally reclaim `RECEIVED` older than N seconds, which also shrinks the documented JVM-crash limit.

## Finding 4: Retry + backoff inside a 4-thread / 50-slot sender pool: rejected webhooks, and phase 4 measures the mock
- **Severity:** High
- **Location:** Phase 2, mock-provider knobs table + step 6; Phase 4 run matrix (R1-R4) and Risk "use `MOCK_CALLBACK_DELAY_MS`"
- **Flaw:** `WebhookSender.send` runs on `webhookExecutor` (core 2, max 4, queue 50, default abort policy). Plan adds up to 5 attempts with blocking backoff (200+400+800+1600 ms = 3s) plus optional delay sleep, all on those threads. No sizing change planned.
- **Failure scenario:** 50-200 JMeter threads call mock pay. Any 503 burst pins 4 threads for seconds; queue fills; next `sender.send` throws `TaskRejectedException` in `PayController.pay` -> mock 500, webhook never sent -> payments stuck PENDING, invariant SQL fails, "first bottleneck" in note 09 is the mock's executor, not the gateway. Separately: `MOCK_CREATE_DELAY_MS=2000` (R4) leaves only the last retry (t=3.0s) after tx2; any delay >= 3s (client read-timeout allows 5s) exhausts retries -> `RETRYABLE` forever. Also "retry on non-2xx" retries 401/404/413, which can never succeed.
- **Evidence:** `AsyncConfig.java:24-26`, `WebhookSender.java:48,55-57,69-71` (all `RestClientException` treated alike), `PayController.java:37`, `mock-provider/.../application.yml:7-8`. Plan: "`mock.callback.retry.max-attempts` 5", "`backoff-ms` 200 doubled each attempt".
- **Suggested fix:** Retry only on 5xx/IO; schedule retries (`TaskScheduler`) instead of sleeping in the pool, or size the executor for the phase 4 thread counts with `CallerRunsPolicy`; make backoff total > max create delay; add mock-side counters (sent / retried / rejected) and report them in note 09.

## Finding 5: `docker-compose.yml`, `postman/`, `.env.example` are git-ignored and untracked; R0 baseline and "clean clone" criterion cannot work
- **Severity:** High
- **Location:** Phase 4, steps 8-9 + Success Criteria ("works headless from a clean clone with compose running"); Phase 1/2 "Related Code Files" (Modify `postman/...`, `docker-compose.yml`)
- **Flaw:** Plan treats these as repo files. They are not in git, so a tag/worktree/clone has none of them.
- **Failure scenario:** `git worktree add ../flux-pay-increment-1 increment-1-done` yields a tree with no compose file and no `.env`; "start that stack" fails. Workaround = hand-copy today's compose (already edited in phase 2) and `.env` (holds `FLUXPAY_SECRET_KEY`) into a sibling dir: "before" stack no longer equals increment 1, and the key file is duplicated outside the project. Phase 1/2 Postman and compose edits are never committed or reviewable. New `docker-compose.loadtest.yml` would be tracked while its base file is not.
- **Evidence:** `.gitignore:32-34` (`.env.example`, `docker-compose.yml`, `postman`); `git ls-files docker-compose.yml postman .env.example` -> empty; `git log --all -- <those paths>` -> empty; `README.md:28-29,45,76` assume they exist.
- **Suggested fix:** Decide before phase 1: un-ignore and commit compose + postman + `.env.example` before tagging `increment-1-done`, or state in plan that they are local-only and give R0 an explicit recipe (which compose file, which env, DB volume/project name isolation).

## Finding 6: Metrics "bound to localhost" has no mechanism; override exposes unauthenticated `/actuator/metrics` on all interfaces
- **Severity:** Medium
- **Location:** Phase 4, sections "Architecture" (compose override env), "Risk Assessment" ("override file only, bound to localhost"), "Security Considerations"
- **Flaw:** Actuator shares the app port; gateway port is published without a host IP; auth filter covers `/api/*` only. An env-only override changes exposure, not binding. Compose merges `ports` lists by appending, so an override cannot narrow `8080:8080` without `!override`.
- **Failure scenario:** `docker compose -f docker-compose.yml -f docker-compose.loadtest.yml up` on a shared network: anyone on the LAN reads `http://<laptop>:8080/actuator/metrics/...` (request counts by uri/status, pool state) with no credentials. Stack left running after a load session (`restart: unless-stopped`) keeps it exposed.
- **Evidence:** `docker-compose.yml:35-36` (`"8080:8080"`; contrast db `:12` `127.0.0.1:5432:5432`), `docker-compose.yml:25,45` (restart policy), `application.yml:28-32` (no `management.server.port`), `MerchantAuthFilterConfig.java:36` (`/api/*` only).
- **Suggested fix:** In the override set `MANAGEMENT_SERVER_PORT=9090` and publish `127.0.0.1:9090:9090`; point `collect-metrics.sh` there. Add `docker compose down` to `run.sh` teardown notes.

## Finding 7: `reclaimRetryable` trusts `event_id` alone: no byte equality, no attempt cap
- **Severity:** Medium
- **Location:** Phase 2, sections "Requirements" ("same `event_id`, same bytes") + "Architecture" (`reclaimRetryable` SQL) + "Security Considerations" ("RETRYABLE rows are bounded")
- **Flaw:** Requirement says same bytes; SQL checks only `(provider_code, event_id, processing_status)`. Redelivery body is what gets validated and applied, stored `raw_body` stays from delivery 1. Rows are bounded, reprocessing per row is not: no attempt counter, and `duplicate_count` is not touched on reclaim. Webhook signature has no freshness element, so a captured `RETRYABLE` delivery stays replayable indefinitely.
- **Failure scenario:** (a) Signed sender reuses eventId with a different body (status/amount) after a 503: ledger shows body 1, payment got body 2; audit trail of "exact signed bytes" is wrong. (b) Poison event that throws after insert -> `RETRYABLE(INTERNAL_ERROR)` -> every replay re-runs tx A + lookups + tx C; no count recorded, nothing ever parks it.
- **Evidence:** `V4__create_webhook_events.sql:4` ("raw_body is stored verbatim so the exact signed bytes are auditable"), `WebhookEventStore.java:36-52` (raw body written only on first insert), `MockProviderAdapter.java:79-81` + `HmacSigner.java:36-43` (HMAC over body only, no timestamp), `WebhookIngestionService.java:124` (any non-"SUCCESS" status maps to FAILED, so a body swap changes the outcome).
- **Suggested fix:** Add `and raw_body = :raw` to the reclaim (mismatch -> 200 `REJECTED EVENT_BODY_MISMATCH`, no reprocess); increment an attempt counter on reclaim and stop at N (`REJECTED RETRY_EXHAUSTED`). No migration needed if `duplicate_count` is reused, else one column.

## Finding 8: `SET LOCAL lock_timeout` cannot take a bind parameter; plan implies a configurable value
- **Severity:** Medium
- **Location:** Phase 2, sections "Key Insights" + "Requirements" (`fluxpay.webhook.lock-timeout: 2s`) + step 9
- **Flaw:** PostgreSQL `SET` is a utility statement and rejects `$1` placeholders. With `JdbcClient.param(...)` the statement fails; the obvious workaround is string-concatenating the property into SQL. Also a Java `Duration` prints as `PT2S`, which PostgreSQL rejects. Plan marks connection sharing [UNVERIFIED] but not this.
- **Failure scenario:** Implementer writes `set local lock_timeout = :t` -> syntax error inside tx B -> every pessimistic-mode webhook becomes `RETRYABLE(INTERNAL_ERROR)` + 500 (default mode is pessimistic). Or concatenates config text into SQL, creating the only non-parameterized statement in a codebase whose stores are fully parameterized.
- **Evidence:** `WebhookEventStore.java:38-51`, `IdempotencyStore.java:40-55` (all SQL bound via `.param`); plan: "use `SET LOCAL lock_timeout` in the same transaction", "`fluxpay.webhook.lock-timeout: 2s`".
- **Suggested fix:** `select set_config('lock_timeout', :ms, true)` with the value as integer milliseconds text (`Duration.toMillis()`); assert in `WebhookPessimisticLockIT` that the setting does not leak to the next pooled connection use.

---

## Fact Checker (sampled claims)

| Phase | Claim | Result |
|---|---|---|
| 1 | `@Version` active on `Payment` | VERIFIED `Payment.java:69-70` |
| 1 | Conflict unhandled on webhook path, event stays `RECEIVED` | VERIFIED `WebhookIngestionService.java:125-134` catches only `IllegalPaymentTransitionException`; tx C at `:86` skipped |
| 1 | Every transition block re-reads inside its lambda | VERIFIED `PaymentOrchestrator.java:108,119,137,180`; `WebhookIngestionService.java:127` |
| 1 | 503 not in `STORABLE_STATUSES` | VERIFIED `IdempotencyFilter.java:45` |
| 1 | Sweeper per-row catch exists | VERIFIED `PaymentOrchestrator.java:166-174` |
| 1 | `AbstractIntegrationTest.hmac` reusable | VERIFIED `AbstractIntegrationTest.java:58` |
| 1 | Note 01 mentions `@Version` reserved | VERIFIED `docs/learning/01-state-machine.md:60` |
| 1 | Micrometer available without new dependency | VERIFIED `gateway/pom.xml:45` + starter-actuator 4.1.2 pom pulls `spring-boot-starter-micrometer-metrics`; no `MeterRegistry` use in `gateway/src` yet |
| 1 | "62 unit + 39 IT" | 39 IT VERIFIED by `@Test` count over 7 `*IT.java`; 62 unit UNVERIFIED (not run) |
| 1 | Tag `increment-1-done` | Does not exist yet (`git tag` empty); planned in step 1 |
| 1 | Postman "pay + cancel fired together via runner" | UNVERIFIED; collection is untracked (Finding 5) and has folders Phase 1-6 only |
| 2 | `processing_status` VARCHAR(20), no CHECK | VERIFIED `V4__create_webhook_events.sql:12` |
| 2 | Early webhook -> `UNKNOWN_PAYMENT` + 200 + dedup burned | VERIFIED `WebhookIngestionService.java:112-115,71-78` |
| 2 | `provider_ref` stored only in tx2 | VERIFIED `PaymentOrchestrator.java:109-112` |
| 2 | Mock sender has no retry today | VERIFIED `WebhookSender.java:31,69-71` |
| 2 | `MockProperties`, `SessionController`, `WebhookSenderTest`, `CountingFakeProvider` exist | VERIFIED (paths present) |
| 2 | `MOCK_CALLBACK_DELAY_MS` knob exists | VERIFIED `mock-provider/.../application.yml:28` |
| 2 | `fluxpay.webhook.*` keys | New; absent from `application.yml` (expected) |
| 2 | `findByIdForUpdate` | New; only `loadForSweep` exists `PaymentRepository.java:31` |
| 2 | `docker-compose.yml` pass-through env edit | FAILED as a repo change: file untracked (Finding 5) |
| 3 | `complete`/`delete` match `(merchant_id, idempotency_key)` only | VERIFIED `IdempotencyStore.java:99-118` |
| 3 | Caller lines `IdempotencyFilter.java:77,97,103` | VERIFIED |
| 3 | Caller lines `IdempotencyService.java:47,68,76,81` | VERIFIED |
| 3 | `IdempotencyServiceIT.java:59` (`complete`), `IdempotencyIT.java:97` (`begin`) | VERIFIED |
| 3 | No JPA entity for `idempotency_keys` (validate unaffected) | VERIFIED `V6__create_idempotency_keys.sql:9-10`; store uses `JdbcClient` |
| 3 | Next migration is V8 | VERIFIED (V1-V7 present) |
| 3 | Note 05 "Giới hạn đã biết", journal "monotonically increasing lease token" | VERIFIED `05-idempotency.md:55`, journal `:43` |
| 3 | `ux_payments_live_order` backstop, CREATED counts as live | VERIFIED `V7__add_live_order_unique_index.sql:10-12` |
| 3 | "`lease_epoch` (monotonic)" | FAILED by design (Finding 1) |
| 4 | Provider call made with no tx open | VERIFIED `PaymentOrchestrator.java:94-97` |
| 4 | Canonical string + epoch-millis timestamp | VERIFIED `README.md:64-67`, `MerchantAuthFilter.java:141-142` |
| 4 | Actuator on classpath, default exposure `health` only | VERIFIED `gateway/pom.xml:45`, `application.yml:28-32` |
| 4 | `scripts/burst-replay.sh` exists | VERIFIED |
| 4 | JMeter at `/opt/homebrew/bin/jmeter` | VERIFIED (symlink to 5.6.3) |
| 4 | Dev secrets already in repo | VERIFIED `V2__seed_demo_merchant.sql:9`, `AbstractIntegrationTest.java:42`, `application.yml:54` |
| 4 | "clean clone with compose running" | FAILED (Finding 5) |
| 4 | Metrics "bound to localhost" | FAILED (Finding 6) |

## Contract Verifier

**A. Webhook HTTP contract: always-200 -> 200 | 503 (+ 500 marks RETRYABLE)**
Consumers found:
- `WebhookController.java:29,37,41` (return type) -- in plan
- `WebhookResult.java:8-20` -- in plan
- `WebhookIngestionService.java:59,77,95` -- in plan
- `mock-provider/.../WebhookSender.java:61-71` -- in plan
- `mock-provider/.../WebhookSenderTest.java:39` (`ExpectedCount.times(3)` for duplicates) -- in plan
- `WebhookIngestionIT.java:110,121,122,148,159,172,182,184` (`isOk()`); stay valid only if none hit the CREATED fallback -- NOT listed as needing a check
- Javadoc stating "always 200": `WebhookController.java:14-15,40`, `WebhookResult.java:4-6`, `WebhookIngestionService.java:35-36` -- NOT listed
- `docs/system-architecture.md:141` -- covered in phase 4 step 12
- `docs/learning/03-webhook-ipn.md` -- cited as prior note, NOT listed for update
- `postman/flux-pay.postman_collection.json:425` (Phase 4 - Webhook folder) -- new folder planned; existing folder's tests not mentioned; file untracked

**B. Idempotency signatures (`claim`, `takeOverExpiredLease`, `complete`, `delete` -> `deleteOwned`, `IdempotencyOutcome.ofNew`)**
All callers: `IdempotencyService.java:47,48,68,69,76,81`; `IdempotencyFilter.java:77,84,97,103`; `IdempotencyServiceIT.java:59`; `IdempotencyOutcome.java:17,21`. Matches plan's list. `abandon` has no test caller. `PaymentExpirySweeper.java:38` uses only `purgeExpired` (unchanged; purge stays unfenced by design).

**C. `PaymentRepository` additions**
`loadForSweep` callers: `WebhookIngestionService.java:127`, `PaymentOrchestrator.java:180`. Bare `findById` is not used anywhere in `gateway/src` today; the repository javadoc `PaymentRepository.java:13-16` says "never a bare findById". Phase 2 fallback introduces the first one (system path): update that javadoc or add a named system-path method.

**D. Config / env**
- `fluxpay.webhook.lock-mode`, `lock-timeout`: `application.yml` listed; `application-test.yml` not listed (fine if defaults live in main yml); compose passthrough only via untracked file.
- `mock.callback.retry.*`, `auto-pay`, `mock.create.delay-ms`: `MockProperties.java:12-19` record shape changes (constructor callers: binding only) -- in plan.
- `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE`: override only (Finding 6).

## Unresolved questions
1. Are `docker-compose.yml`, `postman/` and `.env.example` ignored on purpose (`.gitignore:32-34`)? Decides Finding 5's fix.
2. Finding 3: is 503 for in-flight `RECEIVED` acceptable, given a JVM-crash `RECEIVED` row would then be retried until attempts run out instead of acked?
3. Finding 1: sequence-based epoch or owner UUID? Research ranked epoch first for teaching value; sequence keeps the "number that only goes up" story.
