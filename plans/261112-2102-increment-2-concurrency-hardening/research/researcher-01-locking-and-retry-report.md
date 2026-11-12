# Research: optimistic lock + bounded retry vs pessimistic (Flux-pay, increment 2)

Scope: Boot 4.1.2 (root pom parent), Spring Framework 7, Hibernate 7.x, PostgreSQL 16. 5 web lookups used. Code read: root/gateway pom, Payment, PaymentOrchestrator, PaymentStateMachine, WebhookIngestionService, PaymentRepository, ConcurrentCreateIT.

## Verified from repo

- gateway/pom.xml: no spring-retry, no AOP-retry dependency. Boot-managed versions only.
- Payment: `@Version private long version`. Conflicts raise at UPDATE, nothing catches them.
- PaymentStateMachine.transition inserts the audit row via `transitions.save` in the caller's tx (REQUIRED). Audit and status update share one tx.
- WebhookIngestionService.validateAndApply: tx B does `loadForSweep` (fresh read, so version is current), transition, catches only `IllegalPaymentTransitionException`. An optimistic failure would escape as a 500.
- PaymentOrchestrator.sweepOne catches RuntimeException per row (conflict = logged skip, next sweep retries).

## Q1. Exception on @Version conflict

- Hibernate throws the JPA `OptimisticLockException` (internal cause: stale-state, Hibernate `StaleObjectStateException`) when the UPDATE affects 0 rows. Timing: at flush. Flush happens at `saveAndFlush`, or at commit for `TransactionTemplate` callbacks that do not flush explicitly. (Hibernate flushes on commit; high confidence, UNVERIFIED for the exact 7.x patch.)
- Caller-visible type (Spring): `org.springframework.orm.ObjectOptimisticLockingFailureException`, usually the subclass `JpaOptimisticLockingFailureException`.
  - Repository proxies translate exceptions, so `saveAndFlush` inside `tx.execute` throws the Spring type.
  - Commit-time failure goes through `JpaTransactionManager.doCommit` translation. Recalled as translated, not verified in this run (source fetch not done). Mark UNVERIFIED; the test in Q4 settles it.
  - Do NOT catch `StaleObjectStateException` or `jakarta.persistence.OptimisticLockException` directly. Catch `org.springframework.dao.OptimisticLockingFailureException` (parent of ObjectOptimisticLockingFailureException). Catching broader `DataAccessException` would also swallow real errors.
- Audit row: YES, rolls back. Same tx, same connection. Flush order runs inserts before updates, so the audit INSERT may have run, but the tx rolls back. Nothing persists. Only holds because `transitions.save` is in the caller's tx (it is).
- Sources: Spring JpaOptimisticLockingFailureException javadoc https://docs.spring.io/spring-framework/docs/7.0.0-RC1/javadoc-api/org/springframework/orm/jpa/JpaOptimisticLockingFailureException.html

## Q2. Retry mechanism

Facts:
- Spring Framework 7 ships resilience in core: `org.springframework.core.retry.RetryTemplate`, `RetryPolicy` builder (`maxRetries`, `delay`, `jitter`, `multiplier`, `maxDelay`, `includes`/`excludes`, `predicate`), `RetryListener`, `@Retryable` + `@EnableResilientMethods`. Source: https://docs.spring.io/spring-framework/reference/core/resilience.html
- `maxRetries=N` means 1 + N attempts. Default: retry any exception, 3 retries, 1s delay.
- `invoke` rethrows the last original exception. `execute` wraps in `RetryException`.
- spring-retry: not mentioned in Spring 7 docs. Not in gateway pom. Treat as a separate, extra dependency (not verified whether it is deprecated).
- `@Retryable` relies on a proxy. Self-invocation (same class) silently skips retry. Our tx callbacks are not `@Transactional`, so the retry would have to wrap a bean method from outside.

Ranking (for this codebase):
1. **Hand-written bounded loop.** Zero new API surface, ~10 lines, easy to instrument (count conflicts per attempt). Best for a learning-first project; no unverified class in the path. Use this for increment 2.
2. **Spring core `RetryTemplate`.** No new dependency on Boot 4.1 (core artifact; confirm `spring-core` version on classpath). Gives `RetryListener` for conflict metrics. Upgrade only if backoff/listeners are needed.
3. **`@Retryable`.** Needs `@EnableResilientMethods`, proxy-only, and the retry boundary must be a separate bean. Adds a trap (self-invocation) for no gain here.
4. **spring-retry.** Extra dependency for something core now covers. Reject.

Loop sketch (shape only, not for implementation):
```java
for (int attempt = 1; ; attempt++) {
    try {
        return tx.execute(s -> applyOnce(paymentId, target)); // fresh tx, fresh EntityManager, re-read
    } catch (OptimisticLockingFailureException ex) {
        conflicts.increment();
        if (attempt >= MAX_ATTEMPTS) throw ex;
        // no sleep needed at low contention; add jittered backoff only if load test shows storms
    }
}
```
Rules:
- Each attempt = new tx + re-read. Never reuse the entity from the failed tx: its session is dead after a failed flush, and its `version` is stale (a merge would overwrite the winner's status).
- Retry only `OptimisticLockingFailureException`. Do NOT retry `IllegalPaymentTransitionException`: after reload, the winner's terminal status makes the state machine reject, which is the correct answer (webhook vs cancel race -> ILLEGAL_TRANSITION).
- Put the retry loop around the tx, not inside the state machine. Keep `PaymentStateMachine` unchanged.
- Choose the exhausted outcome explicitly: webhook -> reject reason (e.g. CONFLICT_RETRY_EXHAUSTED) or 500 (provider redelivers). Document which.

## Q3. Pessimistic locking (Spring Data JPA, PostgreSQL)

- Use `@Lock(LockModeType.PESSIMISTIC_WRITE)` on a `@Query` method returning the entity (not an id). Lock applies to the select.
- Generated SQL: `select ... for update` expected for PESSIMISTIC_WRITE. Whether Hibernate 7 emits `FOR NO KEY UPDATE` on PostgreSQL: UNVERIFIED. Known background: community reports (Vlad Mihalcea explicit-locking tag https://vladmihalcea.com/tag/explicit-locking/ ; Hibernate discourse https://discourse.hibernate.org/t/too-aggressive-pessimistic-write-lock-in-postgresql/1824 ; HHH-13135 https://hibernate.atlassian.net/browse/HHH-13135) say FOR UPDATE is too strong because it blocks FK child inserts. Verify: enable Hibernate SQL logging in the test.
- Timeouts:
  - Hibernate maps SQLSTATE per dialect (fetched from Hibernate PostgreSQLDialect source, main branch, `@since 8.0`, minimum PG 15): `55P03` (lock not available) -> `jakarta.persistence.LockTimeoutException`; `40P01` (deadlock) -> `LockAcquisitionException`; `57014` (cancel/query timeout) -> `QueryTimeoutException`. https://github.com/hibernate/hibernate-orm/blob/main/hibernate-core/src/main/java/org/hibernate/dialect/PostgreSQLDialect.java
  - PostgreSQL honours `NOWAIT` (fails immediately, 55P03) and `SKIP LOCKED` (9.5+). PostgreSQL has NO per-statement wait timeout in SQL. Positive `jakarta.persistence.lock.timeout` values: UNVERIFIED whether Hibernate 7 emits anything for PostgreSQL. Older docs suggest timeout 0 -> nowait, -2 -> skip locked (Hibernate 5 user guide).
  - Reliable option: `SET LOCAL lock_timeout = '2s'` (native query, inside the tx). Bounds wait for every lock in that tx. Pair with NOWAIT if fail-fast is wanted.
  - Exception on timeout: Spring `PessimisticLockingFailureException` (CannotAcquireLockException family). Map `LockTimeoutException` / `LockAcquisitionException` to it. Verify in test.
- `@Version` with pessimistic: version still checked and incremented on dirty update (Hibernate standard behaviour; PESSIMISTIC_FORCE_INCREMENT only forces a bump with no change). Lock prevents the conflict, so the version check should never fail under the lock. Confidence high, UNVERIFIED in this run.
- Deadlock risk: one row per tx -> no lock-ordering cycle on `payments`. Residual risk: a FOR UPDATE on payments blocks FK `FOR KEY SHARE` from `payment_transitions` inserts by other tx on the same payment. Only same-payment concurrency is affected. Low.

## Q4. Deterministic interleaving test

Recipe (no production hooks needed; the test owns the race):
- Use `TransactionTemplate` in the test, NOT `@Transactional` on the test (an outer tx hides the race).
- Thread A: `tx.execute`: load Payment (version 0) -> `readDone.countDown()` -> `awaitLatch(bDone)` -> `stateMachine.transition(...)` -> commit.
- Main/thread B: `readDone.await()` -> separate `tx.execute` that transitions the same payment and commits (version 1) -> `bDone.countDown()`.
- Result is fixed every run: A's UPDATE `where version=0` affects 0 rows -> `OptimisticLockingFailureException`. Assert exception type, audit row count unchanged for A, final status = B's.
- Retry test: same race, but A's retry runs after B; second attempt sees terminal status -> `IllegalPaymentTransitionException`. Assert it is NOT retried.
- Pessimistic comparison (deterministic via NOWAIT): thread B holds `select ... for update` open (latch), thread A with `lock_timeout`/NOWAIT must fail with `PessimisticLockingFailureException` while B is still open. Then release B.
- Hooks: `org.hibernate.stat.Statistics` (`getOptimisticFailureCount()`, enable `hibernate.generate_statistics=true`) counts conflicts; use it to assert counts and to observe rate under load. API presence in 7.x: UNVERIFIED (from memory).
- Style: mirror ConcurrentCreateIT (AbstractIntegrationTest, Testcontainers Postgres 16, CountDownLatch gate, ExecutorService, `done.await(timeout)`, `shutdownNow` in finally). Use latches for ordering, not sleeps.

## Q5. Trade-off table (webhook terminal-status write: low contention per row, short tx)

| Dimension | Optimistic + bounded retry | Pessimistic FOR UPDATE |
|---|---|---|
| Lock held | none during read; only at UPDATE check | row lock from read to commit |
| Throughput at low contention | best (no waits) | fine; small lock cost |
| Same-row contention | conflict -> retry (CPU + extra SELECT per retry) | serialized; waiters block |
| Failure mode | `ObjectOptimisticLockingFailureException` after N tries | wait / `PessimisticLockingFailureException` on timeout |
| Deadlock | none (no locks held) | none with one row/tx; FK-insert blocking risk |
| Code | loop + exception catch (~10 lines) | `@Lock` on one query (~3 lines) + `lock_timeout` |
| Load-test signal | retry count per request, conflict rate (Statistics) | lock wait time (pg_stat_activity / lock waits) |
| Correctness | relies on re-run of state machine after reload | correct by construction |
| Cost of mis-tuned N | exhaustion -> 500 / rejection | long waits -> provider timeouts |

Expected under load (hypothesis, to measure): webhook duplicates/cancel races are rare per row, so retry count ~0 most of the time; optimistic should win on p99. Pessimistic wins only when conflicts are frequent on the same row (e.g. burst of duplicate webhooks for one payment). Measure, do not assume.

## Recommendation (ranked)

1. **Optimistic lock + hand-written bounded retry** (MAX_ATTEMPTS 3) for webhook tx B, cancel, and sweeper. Matches roadmap, KISS, no new dependency, low contention. Count conflicts.
2. **Pessimistic `PESSIMISTIC_WRITE` + `SET LOCAL lock_timeout`** on the webhook path only, as the comparison arm of the load test. Do not ship both paths; keep one behind a flag or a branch for the experiment.
3. **spring-retry or `@Retryable`**: reject for this increment.

## Limitations

- Hibernate 7.x locking chapter not read (WebFetch truncated; 400k chars). Dialect SQL for `FOR NO KEY UPDATE` and timeout emission is UNVERIFIED for 7.x. Fetched source is `main` (ORM 8 `@since`), not the 7.x release.
- Spring `JpaTransactionManager.doCommit` translation: not read from source. UNVERIFIED.
- Exact Hibernate version pinned by Boot 4.1.2: not checked (no BOM read). Check with `./mvnw dependency:tree` or `spring-boot-dependencies` pom.
- Spring Framework 7 `RetryTemplate` package presence in `spring-core` jar: taken from docs, not inspected.
- No load numbers gathered; all performance statements are hypotheses.

## Unresolved questions

- Does Hibernate 7.x (Boot 4.1.2 version) emit `FOR NO KEY UPDATE` for PESSIMISTIC_WRITE on PostgreSQL? Check with SQL logging in a spike test.
- Does Hibernate emit anything for a positive `lock.timeout` on PostgreSQL? If not, `SET LOCAL lock_timeout` is required.
- Exact exception at commit-time flush (Spring translation path): confirm in Q4 test.
- Should webhook retry exhaustion return 500 (provider redelivers) or a recorded rejection? Product decision, needs user call.

Status: DONE_WITH_CONCERNS
Summary: Recommend optimistic lock + hand-written bounded retry (fresh tx and re-read per attempt, retry only optimistic exception, never retry illegal transition), with pessimistic FOR UPDATE + SET LOCAL lock_timeout as the load-test comparison arm. Several Hibernate 7.x specifics (FOR NO KEY UPDATE, lock timeout emission, commit-time exception translation) remain UNVERIFIED.
Concerns/Blockers: Hibernate 7.x locking docs and source not read within the 5-lookup budget; verify with a spike test before finalizing the plan. Webhook retry-exhaustion outcome needs user decision.

Sources:
- https://docs.spring.io/spring-framework/reference/core/resilience.html
- https://docs.spring.io/spring-framework/docs/7.0.0-RC1/javadoc-api/org/springframework/orm/jpa/JpaOptimisticLockingFailureException.html
- https://github.com/hibernate/hibernate-orm/blob/main/hibernate-core/src/main/java/org/hibernate/dialect/PostgreSQLDialect.java
- https://docs.hibernate.org/orm/7.2/javadocs/org/hibernate/LockMode.html
- https://discourse.hibernate.org/t/too-aggressive-pessimistic-write-lock-in-postgresql/1824
- https://hibernate.atlassian.net/browse/HHH-13135
- https://vladmihalcea.com/tag/explicit-locking/ (secondary, tutorial)
