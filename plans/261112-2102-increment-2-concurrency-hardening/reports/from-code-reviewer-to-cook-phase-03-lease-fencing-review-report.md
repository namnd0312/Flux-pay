# Phase 3 lease fencing - code review

Scope: `git diff HEAD` + untracked (V8 migration, 4 idempotency main files, LeaseFencingIT, IdempotencyServiceIT, notes 05/08). Read-only review. Tests NOT re-run by reviewer; mutation outcomes below are reasoned from source, not executed.

## Verdict

Fence is correct. No Critical/High. No interleaving found where a stale owner writes/deletes, or where the current owner is wrongly refused. Findings are test robustness, one pre-existing filter error-path defect, and small polish.

## Real defects

### M1 (Medium, test) - `fake.calls()` assertion is order-dependent
`gateway/src/test/java/com/fluxpay/gateway/idempotency/LeaseFencingIT.java:154`
- `fake.reset()` runs only in `@AfterEach`. `PaymentLifecycleIT` and `WebhookIngestionIT` create payments through the provider and never reset the fake (only the clock). Spring context is shared, so the counter leaks.
- Failure: failsafe runs one of those classes right before `LeaseFencingIT`, and JUnit picks `stalledCreateRequestKeepsItsOwnResponseButStoresNothing` as the first method -> `calls()` is N+1, test red. Class order is filesystem-dependent (macOS vs Linux CI), method order is deterministic but not source order. Green today by luck of ordering.
- Same latent weakness already in `IdempotencyIT:78,134` and `ConcurrentCreateIT:92` (pre-existing).
- Fix: `fake.reset()` in a `@BeforeEach` (or first line of the test, as `PaymentCreateWithProviderIT:57` does). Cheapest global fix: add the missing `fake.reset()` to the two leaking classes.

### M2 (Medium, pre-existing, not introduced here) - `abandon()` throwing in `finally` masks the original exception and skips `copyBodyToResponse()`
`gateway/src/main/java/com/fluxpay/gateway/idempotency/IdempotencyFilter.java:106-112`
- Same shape before this change (old code: `idempotency.abandon(...)` then `copyBodyToResponse()` in the same `finally`). So: not a regression, but the answer to "copyBodyToResponse always reached" is NO.
- Failure: DB outage -> controller advice renders 500/503 problem body into the wrapper -> non-storable -> `abandon()` hits the same dead DB and throws -> cached body never copied, exception escapes the filter, client gets the container's generic error instead of the problem+json; if the chain itself threw, that exception is replaced by the abandon one. The correlation (non-storable status caused by the DB, abandon needs the DB) makes this the likely case, not a rare one.
- Fix (small): nest so the copy always runs and abandon failure is logged, not thrown:
  `finally { try { if (!settled && !abandon(...)) leaseLost(...); } catch (RuntimeException e) { log.warn("could not release key; lease will expire", e); } finally { wrapper.copyBodyToResponse(); } }`
  Key then stays IN_PROGRESS until the lease expires, which is exactly what the lease is for. Could be done in this phase or deferred; flagging because the phase touched these lines.

## Behaviour change to confirm (Low)

### L1 - `complete()` throwing no longer releases the key
`IdempotencyFilter.java:101-102`
- `settled = true` is set BEFORE `complete()`. Before: `stored` was set after, so a throwing `complete` fell into `abandon` (key deleted, immediate retry possible). Now: no abandon; key stays IN_PROGRESS up to 60s, retries get `409 IDEMPOTENCY_IN_PROGRESS`, then a take-over re-runs and stores `409 ORDER_ALREADY_EXISTS` (for a 201 original).
- `copyBodyToResponse()` is still reached and flushes, so the client gets its 201 committed; the exception then propagates to the container (logged, cannot alter the committed response).
- Reviewer view: acceptable and arguably better than before (no second DB call on a failing DB; no exception masking; an ambiguous-commit `complete` followed by a fenced `abandon` would only produce a bogus "lease lost" WARN). But it is an undocumented change: the comment on line 101 explains the zombie case, not the exception case. Suggest one sentence in the comment / as-built notes. Optional: catch + log in the filter so a store failure after a successful create does not surface as a container error.

## Optional improvements (Low)

- **L2 tests 1 and 2 do not isolate the epoch predicate.** `LeaseFencingIT.java:70-97`: B completes BEFORE the zombie writes, so `status = 'IN_PROGRESS'` alone refuses A. Dropping `and lease_epoch = :e` from `complete` (note 08 exercise 1 / phase step 8) leaves tests 1, 2 and the filter-level test GREEN; only `zombieCannotTouchAFreshClaimMadeAfterTheKeyWasReleased` goes red. Nothing is unguarded (test 3 catches both the `complete` and `deleteOwned` epoch mutations; test 1's `isGreaterThan` catches a take-over that does not re-issue), but the canonical zombie interleaving has no direct test: A writes while B is still IN_PROGRESS. Add: A claim, +61s, B take-over, `complete(epochA)` false, `abandon(epochA)` false, `complete(epochB)` true, replay = B. Make sure the author's expected answer to exercise 1 is "only test 3".
- **L3** `LeaseFencingIT.java:92`: return of B's `complete` not asserted; if it were false the test would still pass on the final REPLAY only by accident of the setup. `assertThat(...).isTrue()`.
- **L4** `V8__add_idempotency_lease_epoch.sql:16`: sequence not owned by the column. Dropping the table/column later leaves an orphan sequence. Add `ALTER SEQUENCE idempotency_lease_epoch_seq OWNED BY idempotency_keys.lease_epoch;` (in V8 while uncommitted).
- **L5** `IdempotencyFilter.java:121`: WARN says "taken over" for every refused write; a refused write can also mean the row was TTL-purged. Wording only ("no longer owns the key").
- **L6 (pre-existing)** `IdempotencyService.java:73-77`: take-over loser returns IN_PROGRESS even when the 0-row result was because the old owner completed between `find` and the update; client gets 409 once, next retry replays. Unchanged by this phase.
- **L7** `claim`: `nextval` is consumed by the column default even when ON CONFLICT discards the insert, so every replay/duplicate burns a sequence value. Harmless (BIGINT, equality-only comparison); gaps are large, so do not read epochs in logs as a count.
- **L8** phase file: frontmatter `status: in-progress` with all todos checked; caller line numbers in "Signature change" are stale. For the lead/planner.

## Checklist (a)-(f)

- **(a) Requirements:** all met. claim (column default) and take-over (explicit `nextval`) return the token; complete/deleteOwned carry `lease_epoch = :e and status = 'IN_PROGRESS'` and report affected rows; zombie -> WARN (merchant, key, epoch; no body), nothing stored/deleted, own response returned; one additive migration, no new table.
- **(b) Interleavings:** no finding beyond M2/L1/L6.
  - Stale owner write/delete: impossible; A's statement either runs before B's take-over (A is still owner, B's update then matches 0 rows on status) or blocks on the row lock and re-checks the predicate against the new epoch -> 0 rows.
  - Current owner refused: only after take-over or TTL purge (intended). `complete` does not test `lease_until`, so an owner past its lease but not yet taken over still completes: correct fencing semantics.
  - Two concurrent take-overs: second blocks, READ COMMITTED re-evaluates `lease_until < :now` on the updated row -> 0 rows -> IN_PROGRESS.
  - `ON CONFLICT DO NOTHING RETURNING lease_epoch`: no row on conflict -> empty; inserted row returns the default. Concurrent first claims: loser waits for the winner's commit, then no-ops and `find` sees the row.
  - Types: `long` binds as int8, `query(Long.class)` reads bigint; sentinel 0 on non-NEW outcomes can never match (sequence starts at 1).
  - "Row purged between claim and read" branch unchanged.
  - Filter paths: storable -> complete, no abandon; non-storable -> fenced abandon; chain throws -> fenced abandon, exception propagates, copy reached; complete throws -> L1; abandon throws -> M2.
- **(c) Migration:** safe. Volatile default forces a table rewrite and is evaluated per row, so each existing row gets a distinct value (all COMPLETED or about to expire; any pre-deploy in-flight request is on old code anyway, covered by the rolling-deploy note). V8 follows V7; no V8 on `main`. Flyway wraps it in one transaction. No JPA entity maps `idempotency_keys`, so `ddl-auto: validate` is unaffected. Only L4.
- **(d) Contract:** all callers updated (`IdempotencyFilter:81,88,102,108`; `IdempotencyServiceIT:58-59`; `IdempotencyIT:97` and `PaymentExpirySweeper:38` unaffected). No equality/identity use of `IdempotencyOutcome` anywhere (only `kind()` comparisons). Replay/mismatch/in-progress paths byte-identical. Epoch appears only in the WARN log, never in a header or body.
- **(e) Tests:** M1, L2, L3. Test 3 is a true ABA guard: with an in-row counter C's epoch equals A's -> `isNotEqualTo(epochA)` and `complete(epochA)).isFalse()` both fail. Filter-level test is deterministic otherwise: A is parked after auth and after tx1 commit, clock moves only while A is parked, B never reaches the provider, the WARN is emitted on A's thread before `a.get()` returns, the key is unique so captured output from other tests cannot satisfy the assertion, scheduling is off in the test profile, payment expiry (15m / 2m) is far above 61s.
- **(f) Note 08:** no wrong statement found. Sequence values are not rolled back (true). ABA diagram matches test 3. Exercise 2 ("only one test red") holds: tests 1, 2 and the filter test stay green with an in-row counter. Exercise 1 is an open question; its answer is L2. Timeouts "2s + 5s" match `application.yml:7-8`.

## Unresolved questions

1. L1: is "store failure after a successful create leaves the key leased for 60s" the intended behaviour? If yes, say so in the comment/as-built notes.
2. M2: fix in this phase (three lines, same block) or defer to a later hardening phase?

Status: DONE_WITH_CONCERNS
Summary: Fencing logic and migration are correct; no way found for a stale owner to write/delete or for the current owner to be refused. Concerns are an order-dependent test assertion, a pre-existing finally-block masking bug in the filter, and an undocumented change in what happens when complete() throws.
Concerns/Blockers: M1 can turn the suite red on a different class order (CI on Linux). Nothing blocks commit of the main code.
