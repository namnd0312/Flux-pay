# Red team review: Assumption Destroyer — increment 2 concurrency hardening

Reviewed: plan.md + phase 01-04, against `gateway/src`, `mock-provider/src`, `.gitignore`, `git ls-files`, local `~/.m2` jars (Boot 4.1.2 -> Hibernate 7.4.13.Final). Nothing built or run. Paths below relative to repo root `/Users/admin/Desktop/DEV/AI/Flux-pay`.

## Finding 1: `lease_epoch` restarts at 1 after a delete, so the fence is not monotonic and a zombie write still lands
- **Severity:** Critical
- **Location:** Phase 3, sections "Requirements" + "Architecture" (`claim sets lease_epoch = 1`, `deleteOwned`); plan.md "Key decisions" (`lease_epoch column (monotonic)`)
- **Flaw:** Epoch lives in the row. `abandon` deletes the row; next `claim` inserts a new row with epoch 1 again. Predicate `lease_epoch = :mine AND status = 'IN_PROGRESS'` cannot tell the old epoch-1 owner from the new one (ABA).
- **Failure scenario:** A begins (epoch 1), stalls > 60s. B takes over (epoch 2), gets a non-storable response (500, or the new 503 `CONCURRENT_UPDATE` from phase 1) -> `deleteOwned(epoch 2)` removes the row. Merchant retries: C `claim` -> new row, epoch 1, IN_PROGRESS. Zombie A wakes: `complete(epoch 1)` matches C's row and stores A's response; or A's `abandon(epoch 1)` deletes C's live row and a fourth request runs concurrently with C. Acceptance "Zombie idempotency writes affect 0 rows" is false. Same hole after TTL purge.
- **Evidence:** abandon on every non-storable status: `gateway/src/main/java/com/fluxpay/gateway/idempotency/IdempotencyFilter.java:45,100-104`. Row delete: `IdempotencyStore.java:113-118`; purge `:121-125`. Insert has no carried-over counter: `IdempotencyStore.java:40-46`. Plan: "`claim` sets `lease_epoch = 1`". Planned `LeaseFencingIT` only covers take-over then complete, never delete then re-claim, so it would pass with the bug in.
- **Suggested fix:** Take the token from something that outlives the row: a Postgres sequence (`nextval` in both claim and take-over, V8 creates it) or a random UUID owner token (research option A). Add the delete-then-reclaim case to `LeaseFencingIT`.

## Finding 2: Early-webhook fallback is two unsynchronised reads; the event is still burned if tx2 commits between them
- **Severity:** High
- **Location:** Phase 2, section "Architecture" (`validateAndApply`), and "Risk Assessment" first bullet
- **Flaw:** Flow is: lookup by `(providerCode, providerRef)` empty -> `findById(merchantRef)` -> `CREATED` => RETRYABLE, `else => REJECTED(UNKNOWN_PAYMENT)`. The two reads share no snapshot. The `else` branch treats "PENDING with matching ref" as unknown.
- **Failure scenario:** Read 1 runs before create tx2 commits (provider_ref still null) -> empty. tx2 commits. Read 2 sees PENDING -> `REJECTED UNKNOWN_PAYMENT`, HTTP 200, dedup row burned, provider stops retrying. Exactly the loss this phase exists to remove, only narrower. Most likely moment: a mock retry landing as the delayed create returns. `verify-invariants.sql` will not flag it (row is REJECTED, not RECEIVED/RETRYABLE); payment later EXPIRES with money taken.
- **Second wrong claim, same branch:** Risk Assessment says after the sweeper fails an abandoned CREATED row "a later redelivery is `ILLEGAL_TRANSITION`". False: provider_ref is only ever written in tx2, so the first lookup is still empty, status is FAILED (not CREATED) -> `UNKNOWN_PAYMENT` with `payment_id` NULL. The one event proving money was taken is not linked to the payment that reconciliation (increment 4) needs.
- **Evidence:** reads outside any tx: `webhook/WebhookIngestionService.java:112`; `open-in-view: false` at `gateway/src/main/resources/application.yml:18`. provider_ref set only at `payment/PaymentOrchestrator.java:109-110`; `compensateToFailed` (`:117-124`) and `sweepOne` (`:179-187`) never set it. UNKNOWN_PAYMENT records null payment id: `WebhookIngestionService.java:113-115`.
- **Suggested fix:** After `findById`: CREATED -> RETRYABLE; status != CREATED and `p.getProviderRef()` equals the event's ref -> continue the normal path with `p`; terminal with null provider_ref -> reject with a distinct reason and store `payment_id`. Or resolve by payment id first, then compare provider_ref. Add an IT for "webhook after CREATED -> FAILED".

## Finding 3: "Before" run via `git worktree` cannot start: compose file, `.env`, Postman are git-ignored
- **Severity:** High
- **Location:** Phase 4, "Key Insights", step 8, Success Criteria ("from a clean clone with compose running"); Phase 1 step 1; Phase 1/2 "Related Code Files" (`postman/...`, `docker-compose.yml`)
- **Flaw:** Plan assumes the tag carries a runnable stack. It does not.
- **Failure scenario:** `git worktree add ../flux-pay-increment-1 increment-1-done && docker compose up` -> no compose file, no `.env` (`FLUXPAY_SECRET_KEY` is mandatory). Copying today's compose file in means R0 runs with phase-2 edits to that file, so "before" is not the tagged state. Phase 1/2 edits to `docker-compose.yml` and the Postman collection are unversioned; the new tracked `docker-compose.loadtest.yml` overrides a base that a clean clone does not have. Both stacks also bind 8080/8081/5432 with `restart: unless-stopped`.
- **Evidence:** `.gitignore:9` (`.env`), `:32` (`.env.example`), `:33` (`docker-compose.yml`), `:34` (`postman`); `git check-ignore -v` confirms all four; `git ls-files` lists none of them. Mandatory key: `docker-compose.yml:34`. Ports: `docker-compose.yml:12,36,52`. README still tells users `cp .env.example .env` (`README.md:28-29`).
- **Suggested fix:** Decide first: un-ignore `docker-compose.yml`, `.env.example`, `postman/` and commit them before tagging (this reverses an existing repo choice, so ask the user), or state in step 8 exactly which files are copied into the worktree and snapshot the compose file at tag time. Add "stop the current stack" to step 8.

## Finding 4: Load harness will saturate the mock-provider's 2-4 thread webhook executor before the gateway
- **Severity:** High
- **Location:** Phase 4, "Key Insights" (bottleneck hypothesis), run matrix R0-R4, "Risk Assessment" bullet 2; Phase 2 mock retry/backoff knobs
- **Flaw:** Plan looks for the first bottleneck in Tomcat/Hikari of the gateway and never accounts for the mock's delivery pool: core 2, max 4, queue 50, default abort policy. Delay and (after phase 2) retry backoff sleep on those same threads.
- **Failure scenario:** Risk mitigation says use `MOCK_CALLBACK_DELAY_MS` to line the webhook up with cancel. At 100 ms delay capacity is ~20 webhooks/s (only 2 core threads until the queue is full), 40/s at max. 50-200 JMeter threads exceed it: the queue adds seconds of lag (bounded poll of 10 x 200 ms fails, webhook no longer overlaps cancel, so R1/R2 measure nothing), then `TaskRejectedException` is thrown in the pay request thread -> mock returns 500. Note 09 would name a "bottleneck" that is the test double. The session map is also unbounded for long runs.
- **Evidence:** `mock-provider/src/main/java/com/fluxpay/mockprovider/config/AsyncConfig.java:24-26`; sleep on executor thread `webhook/WebhookSender.java:48,55-56`; synchronous POST per send `:61-67`; `send` invoked from request thread `pay/PayController.java:37`; unbounded map `session/PaymentSessionStore.java:15`.
- **Suggested fix:** Phase 2 or phase 4 step 6: make the executor size/queue configurable and large for load runs, schedule delay/backoff instead of sleeping on the pool, and sample the mock's own latency/5xx in `collect-metrics.sh` so mock saturation is ruled out before blaming the gateway.

## Finding 5: `webhook-vs-cancel.jmx` cannot be built as described; invariant runner assumes a tool that is not installed
- **Severity:** Medium
- **Location:** Phase 4, Requirements scenario table, steps 5 and 7
- **Flaw:** "two parallel samplers (mock pay, cancel) behind a Synchronizing Timer" — a JMeter thread runs its samplers sequentially; Synchronizing Timer groups threads, not samplers in one thread. Needs the third-party Parallel Controller, or two thread groups sharing `paymentId`/`payUrl` across threads. Neither is in the plan. Even with simultaneous release, pay only enqueues an async send (202), so the webhook is not aligned with cancel.
- **Failure scenario:** Junior builds one thread group: pay then cancel run back to back, webhook almost always wins or loses by a fixed margin, R0 shows 0 conflicts, "before/after" has nothing to compare. Step 7 `psql against the compose DB` fails on this machine.
- **Evidence:** JMeter 5.6.3 at `/opt/homebrew/bin/jmeter`; `libexec/lib/ext` contains only `jmeter-plugins-manager-1.9.jar` (no parallel controller). Async 202: `PayController.java:37-38`, `WebhookSender.java:48`. `which psql` -> not found. Fallback "drive the signed webhook directly from JMeter" needs `providerRef`, which is not in the create response (`payment/dto/CreatePaymentResponse.java:10`); it must be cut from the tail of `payUrl` (`session/SessionController.java:40`) — unstated.
- **Suggested fix:** Make the direct signed webhook from JMeter the primary design (two thread groups or one extra plugin, stated explicitly; providerRef from payUrl; secret from `application.yml:54`). Run invariants with `docker compose exec -T db psql`.

## Finding 6: Lock-timeout mechanism rests on an outdated framework claim, and the planned statement cannot take a bind parameter
- **Severity:** Medium
- **Location:** Phase 2, "Key Insights" last bullet, step 9
- **Flaw:** Plan asserts no JPA lock-timeout hint is reliably honoured on PostgreSQL and hand-rolls `SET LOCAL lock_timeout` through `JdbcClient` (plus an [UNVERIFIED] connection-sharing assumption). The Hibernate version actually resolved here ships PostgreSQL connection-level lock-timeout support. Separately, `SET` is a utility statement: `set local lock_timeout = :t` via `JdbcClient.param` (prepared statement) is a syntax error at `$1`, while the value is meant to come from `fluxpay.webhook.lock-timeout: 2s` (a Duration, not a PG literal).
- **Failure scenario:** Junior follows the existing `JdbcClient ... .param(...)` style -> every pessimistic webhook fails with a syntax error -> under the new rules each becomes `RETRYABLE INTERNAL_ERROR` + 500, in the default lock mode.
- **Evidence:** `~/.m2/.../hibernate-core-7.4.13.Final.jar`: `org/hibernate/dialect/lock/internal/PostgreSQLLockingSupport` implements `ConnectionLockTimeoutStrategy`, has `setLockTimeout(Timeout, Connection, ...)` and the literal `set local lock_timeout `; version pinned by `spring-boot-dependencies-4.1.2.pom:71`. Existing param style: `webhook/WebhookEventStore.java:38-51`. Also resolved from the same jar: `PostgreSQLDialect` contains `for no key update` and maps `55P03` next to `LockTimeoutException` — both plan [UNVERIFIED] items lean that way; step 9 must assert on a Spring `PessimisticLockingFailureException` subtype, not a specific class. Not executed here: confirm by SQL log.
- **Suggested fix:** Step 9 first tries `@QueryHints(jakarta.persistence.lock.timeout)` on `findByIdForUpdate` and reads the SQL log; only if that emits nothing fall back to `select set_config('lock_timeout', :ms, true)` (accepts a bind parameter). Drop the JdbcClient connection-sharing assumption if the hint works.

## Finding 7: Several planned ITs cannot be written as described with the current shared fixtures
- **Severity:** Medium
- **Location:** Phase 1 step 2; Phase 2 steps 1, 9, 10; Phase 3 step 7
- **Flaw / failure scenarios:**
  - Phase 1 demo A: "T1 loads payment (version 0) and calls `transition(.., CANCELLED)`". A version-0 payment is CREATED; CREATED -> CANCELLED is not allowed, so T1 throws `IllegalPaymentTransitionException` before any lock conflict. Needs a PENDING row (version 1).
  - Phase 2 step 1: test must POST a webhook with `merchantRef` = payment id while create is parked, but the id is generated inside the orchestrator and only returned in the 201. Plan does not say how to get it (query by `order_id`, or capture in the fake). For "same bytes" redelivery to apply after release, `providerRef` must be pre-computed as `mp_fake_<id>`.
  - Latch on a singleton: `CountingFakeProvider` is one bean in a cached context shared by all ITs. A test failing before release leaves `createPayment` blocked for later classes. Existing ITs assert absolute `fake.calls()` and reset only in `@AfterEach`; new ITs creating 200 payments without `fake.reset()` make those order-dependent.
  - Step 10 "`@TestPropertySource` or parameterized": parameterized cannot switch a property injected at construction. `@TestPropertySource` forks a new context (new fake, new clock, another 20-connection pool); the 200 ms timeout case forks a third.
  - Step 9(a) "webhook thread blocks, release": no latch can observe a DB lock wait; needs a poll on `pg_stat_activity`/`pg_locks`. The 2 s default timeout races a slow CI box.
- **Evidence:** `payment/PaymentStateMachine.java:26`; id creation `payment/PaymentOrchestrator.java:82`; fake ref `gateway/src/test/java/com/fluxpay/gateway/CountingFakeProvider.java:54`, no hook today `:46-56`, bean `:89-93`; shared context `AbstractIntegrationTest.java:35-38,46-52`; absolute count + AfterEach-only reset `idempotency/ConcurrentCreateIT.java:49-53,92`, `IdempotencyIT.java:78,101,134`; no fake reset in `webhook/WebhookIngestionIT.java:52-55`; pool `application-test.yml:6`.
- **Suggested fix:** State in the plan: fake gets `blockNextCreate()` returning a handle exposing the captured `merchantRef`, and `reset()` releases any latch; every new IT resets clock + fake in `@AfterEach`; lock mode switched by `@TestPropertySource` subclasses (accept extra contexts); blocked-state detection by polling `pg_stat_activity`.

## Finding 8: "Metrics endpoint bound to localhost" is not achievable with a compose override as written
- **Severity:** Medium
- **Location:** Phase 4, Requirements (non-functional), "Risk Assessment" last bullet
- **Flaw:** Actuator shares the app port; base compose publishes `8080:8080` on all interfaces. Compose merges `ports` additively, so an override cannot narrow it without `!override`/`!reset`.
- **Failure scenario:** Load-test stack exposes unauthenticated `/actuator/metrics` on the LAN while the plan and note 09 state it is localhost-only.
- **Evidence:** `docker-compose.yml:35-36` (gateway), compare DB which is bound correctly `:11-12`; exposure default `gateway/src/main/resources/application.yml:28-32`.
- **Suggested fix:** Either set `ports: !override ["127.0.0.1:8080:8080"]` in the override, or drop the claim and say "dev laptop only". Low stakes; just make the statement true.

## Fact Checker

| # | Phase | Claim | Result |
|---|---|---|---|
| 1 | 1 | `@Version` active on Payment | VERIFIED `payment/Payment.java:69-70` |
| 2 | 1 | Conflict unhandled -> 500, event stuck RECEIVED | VERIFIED `WebhookIngestionService.java:125-134` (only Illegal... caught), `common/ProblemDetailsHandler.java:91-94` |
| 3 | 1 | Every transition block re-reads inside its own `tx.execute` | VERIFIED `PaymentOrchestrator.java:107-114,118-123,136-141,180`; `WebhookIngestionService.java:126-130` |
| 4 | 1 | Version changes only with a transition; <= 2 transitions; 3 attempts suffice | VERIFIED. Only mutators: `Payment.java:94-112`; provider fields set only with the PENDING transition `PaymentOrchestrator.java:109-112`; `touch` only at `PaymentStateMachine.java:56`; table `:26-32`. A writer can in fact lose only once |
| 5 | 1 | 503 not in `STORABLE_STATUSES` | VERIFIED `IdempotencyFilter.java:45` |
| 6 | 1 | Signed webhook can be POSTed in ITs via the fake | VERIFIED `CountingFakeProvider.java:33,59-72`; `WebhookIngestionIT.java:85-92` |
| 7 | 1 | Cancel not idempotency-gated | VERIFIED `IdempotencyFilterConfig.java:27` |
| 8 | 1 | MutableClock safe across threads | VERIFIED for read-only use `MutableClock.java:19` (volatile; `advance` not atomic, single test thread only) |
| 9 | 1 | "62 unit + 39 IT" | 39 IT VERIFIED (grep `@Test` in `*IT.java`); 62 unit UNVERIFIED (27 `@Test` in `*Test.java`, rest may be parameterized) |
| 10 | 1 | Demo A "version 0 -> CANCELLED" | FAILED (Finding 7) |
| 11 | 2 | provider_code/provider_ref null while CREATED | VERIFIED `PaymentOrchestrator.java:82,109-110` |
| 12 | 2 | Retried webhook after tx2 passes REF/AMOUNT checks | VERIFIED `WebhookIngestionService.java:118,121` |
| 13 | 2 | `processing_status` VARCHAR(20), no CHECK | VERIFIED `V4__create_webhook_events.sql:12`; `reject_reason` VARCHAR(40) `:13` fits new reasons |
| 14 | 2 | Nothing else reads `processing_status` values | VERIFIED: only `WebhookEventStore.java:41,63,72` |
| 15 | 2 | Later redelivery after abandoned CREATED is ILLEGAL_TRANSITION | FAILED (Finding 2) |
| 16 | 2 | No reliable JPA lock-timeout hint on PG | FAILED/outdated for Hibernate 7.4.13 (Finding 6) |
| 17 | 2 | `FOR UPDATE` vs `FOR NO KEY UPDATE` | Dialect jar contains `for no key update`; runtime SQL UNVERIFIED |
| 18 | 2 | Existing mock knobs env-driven | VERIFIED `mock-provider/src/main/resources/application.yml:26-29`; not passed through compose `docker-compose.yml:46-50` (plan covers) |
| 19 | 2 | Sender does not retry today | VERIFIED `WebhookSender.java:69-71` |
| 20 | 3 | complete/delete have no owner check | VERIFIED `IdempotencyStore.java:99-118` |
| 21 | 3 | Caller line numbers | VERIFIED `IdempotencyFilter.java:77,97,103`; `IdempotencyServiceIT.java:59`; `IdempotencyService.java:47,68,76,81`; `IdempotencyIT.java:97` |
| 22 | 3 | No JPA entity for idempotency_keys | VERIFIED (JdbcClient only, `IdempotencyStore.java:26`) |
| 23 | 3 | `lease_epoch` monotonic | FAILED (Finding 1) |
| 24 | 3 | NEW no longer a singleton is safe | VERIFIED: tests compare `.kind()` only (`IdempotencyServiceIT.java:38-81`) |
| 25 | 4 | Provider call outside a transaction | VERIFIED `PaymentOrchestrator.java:94-97` |
| 26 | 4 | Actuator on classpath; metrics available | VERIFIED `gateway/pom.xml:45`; starter pulls `spring-boot-starter-micrometer-metrics`; `DataSourcePoolMetricsAutoConfiguration` (spring-boot-jdbc) and `TomcatMetricsAutoConfiguration` (spring-boot-tomcat) exist in 4.1.2 jars. Tomcat thread metric names UNVERIFIED |
| 27 | 4 | Client gets `paymentId` + `payUrl`; pay endpoint | VERIFIED `CreatePaymentResponse.java:10`; `PayController.java:21,32`; payUrl host `docker-compose.yml:50` reachable from host |
| 28 | 4 | Canonical string / header contract | VERIFIED `README.md:64-67` |
| 29 | 4 | Tag build runnable via worktree + compose | FAILED (Finding 3) |
| 30 | 4 | Metrics "bound to localhost" | FAILED (Finding 8) |
| 31 | 4 | JMeter installed | VERIFIED 5.6.3; `psql` NOT installed on host |

## Contract Verifier

| Change | Consumers found | Missed by plan |
|---|---|---|
| `IdempotencyService.complete/abandon` signatures | `IdempotencyFilter.java:97,103`; `IdempotencyServiceIT.java:59` | none |
| `IdempotencyStore.claim/takeOverExpiredLease/complete/delete` | `IdempotencyService.java:47,68,76,81` | none (`deleteExpired` `:86` untouched, caller `PaymentExpirySweeper.java:38`) |
| `IdempotencyOutcome.ofNew()` -> `ofNew(long)`, new component | `IdempotencyService.java:48,69`; canonical ctor at `IdempotencyOutcome.java:17-19,34` | plan does not mention the three constants + `replay()` must pass an epoch value; trivial |
| `WebhookResult` + `retryable` | factories `WebhookResult.java:11,15,19`; `WebhookIngestionService.java:77,95`; `WebhookController.java:37` | none |
| Webhook HTTP 200-always -> 503 possible | `WebhookSender.java:61-71` (plan modifies); `WebhookSenderTest`; stale "always 200" in `WebhookController.java:14,40`, `WebhookResult.java:4-6`, `WebhookIngestionService.java:35-36`, `docs/learning/03-webhook-ipn.md:30`, `docs/system-architecture.md:141,171` | `docs/learning/03-webhook-ipn.md` and the three Javadocs not in any modify list |
| `MockProperties` / `Callback` record shape | `WebhookSenderTest.java:34-35` (positional ctor), `WebhookSender.java:55,58`, `AsyncConfig.java:18` | none (test listed); defaults for new record fields must come from yml, record binding gives 0/null |
| `WebhookSender.send` new caller (auto-pay) | today only `PayController.java:37` | none |
| `OptimisticLockingFailureException` handler | `ProblemDetailsHandler.java` handlers all return bare `ProblemDetail`; `Retry-After` needs `ResponseEntity<ProblemDetail>` | not stated; minor |
| `docker-compose.yml`, `postman/*` edits | git-ignored (`.gitignore:33-34`) | yes (Finding 3) |

## Unresolved questions
1. Are `docker-compose.yml`, `.env.example`, `postman/` ignored on purpose? Finding 3's fix depends on it (user decision, do not flip silently).
2. Epoch source: sequence or UUID owner token? Research ranked the in-row counter first without considering delete; needs a re-pick.
3. Is the 62 unit-test count from a real surefire run? Only 27 `@Test` methods found by grep.
4. Does a Hibernate 7.4 query-level `jakarta.persistence.lock.timeout` hint actually emit `set local lock_timeout` for a Spring Data `@Lock` query? Jar shows the support class; behaviour not executed here.

Status: DONE_WITH_CONCERNS
Summary: 8 findings (1 Critical, 3 High, 4 Medium), all with file:line evidence. Phase 1's "3 attempts suffice" and Phase 3's caller list hold; Phase 3's fence design, Phase 2's fallback lookup and Phase 4's "before" run and load validity do not.
Concerns/Blockers: Findings 4-6 rest partly on framework/JMeter behaviour read from jars and config, not executed; confirm in the first step of each phase.
