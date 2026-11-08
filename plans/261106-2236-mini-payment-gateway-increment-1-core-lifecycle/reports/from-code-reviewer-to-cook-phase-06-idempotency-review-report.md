# Phase 6 "Idempotency" — Code Review (advisory, no code changed)

Reviewer: code-reviewer | Date: 2026-11-07
Scope: gateway idempotency filter/service/store, migrations V6/V7, orchestrator create() + sweeper, problem advice, tests.
Verdict: **SHIP** with two Medium hardening notes. No Critical/High defects. No double-charge path found.

## Verification run

`./mvnw -pl gateway -Dtest=IdempotencyServiceIT,IdempotencyIT,ConcurrentCreateIT test` -> **BUILD SUCCESS**, 14 tests, 0 failures. ConcurrentCreateIT (50 threads / pool 20) green in 5.5s — no connection-pool deadlock, no timeout. (Full `verify` not run; targeted Phase 6 suites + module compile pass.)

## Acceptance criteria — all verified

1. **50 concurrent identical creates -> 1 row + 1 provider call.** PASS. No deadlock risk: `begin()` is `@Transactional` and commits the `INSERT ... ON CONFLICT DO NOTHING` claim *before* the filter runs the chain (`IdempotencyFilter.java:77` returns, then `:84`/`:93`), so the DB connection is released before the controller/provider work. The winner never waits on a loser; losers only wait on the winner's short claim txn (`IdempotencyStore.java:40-56`). No wait cycle -> no deadlock. Losers resolve to 409 (fresh lease) or 201 (replay). Test asserts `isSubsetOf(201,409)` and `rows==1`, `fake.calls()==1`.
2. **Stored 502 replayed without a 2nd provider call.** PASS. 502 is in `STORABLE_STATUSES` (`IdempotencyFilter.java:45`); `providerFailureIsStoredAndReplayedWithoutSecondCall` asserts `fake.calls()==1` on replay and `Idempotency-Replayed: true`.
3. **Terminal order re-creatable; live order + new key -> 409.** PASS. Partial index `WHERE status IN ('CREATED','PENDING','SUCCEEDED')` (`V7__add_live_order_unique_index.sql:10-12`) excludes FAILED/EXPIRED/CANCELLED; `create()` maps `DataIntegrityViolationException -> OrderAlreadyExistsException` (`PaymentOrchestrator.java:88-92`). Tests `failedOrderIsRecreatableWithNewKey` + `duplicateLiveOrderWithNewKeyIs409`.
4. **copyBodyToResponse on every path; replay writes stored body + header; no filter-internal 500.** PASS. `copyBodyToResponse()` is in `finally` (`IdempotencyFilter.java:105`). Reject/replay use the shared `ProblemDetailWriter`; begin failures never throw to the client.
5. **Lease take-over only when expired; fresh lease -> 409; conditional UPDATE is sole arbiter.** PASS. Fresh lease short-circuits to `inProgress()` (`IdempotencyService.java:65-66`); expired attempts `takeOverExpiredLease` whose `UPDATE ... WHERE status='IN_PROGRESS' AND lease_until < :now RETURNING` (`IdempotencyStore.java:84-96`) atomically picks one winner. Verified by `expiredLeaseIsTakenOver`.
6. **No regression.** Phase 6 suites green; build compiles.

## Threat-model checks (double-charge / lost-update)

- **Can two requests both charge the provider?** No. `create()` does tx1 `saveAndFlush` *before* `provider.createPayment()` (`PaymentOrchestrator.java:88` then `:97`). A second logical attempt (lease take-over, or a different-key duplicate) fails tx1 on `ux_payments_live_order` and throws before reaching the provider call. Traced the stall/timeout/take-over interleavings — in every case at most one `createPayment()` runs per live (merchant, order). The partial unique index, not the idempotency key, is the real charge guard; it is correctly placed upstream of the provider call.
- **request_hash integrity.** The hash is `sha256(raw body)` computed by the filter from the same cached buffer the auth filter signed (`IdempotencyFilter.java:76` reads `CachedBodyHttpServletRequest` bytes; `CanonicalRequest.build` bakes `sha256hex(body)` into the signed payload, `CanonicalRequest.java:40`). Same key + same body -> same hash (replay); different body -> MISMATCH 422. SHA-256 collision is not a practical concern. `getInputStream()` returns a fresh `ByteArrayInputStream` each call, so the filter's read does not starve the controller's `@RequestBody` (`CachedBodyHttpServletRequest.java:39-62`).
- **Stuck IN_PROGRESS beyond lease.** Normal exceptions are handled inside DispatcherServlet, so the chain returns with a status and the filter stores or abandons. Only a hard JVM death between `begin()` and `complete()` leaves a stuck row; the 60s lease recovers it via take-over. Confirmed acceptable as designed.
- **abandon() on non-storable 500.** Frees the key via unconditional `DELETE` (`IdempotencyStore.java:113-118`), so the merchant may retry. Confirmed.
- **Replay content-type for 502.** `replay()` picks `problem+json` for status>=400, `json` otherwise (`IdempotencyFilter.java:111-112`); matches how the advice/MVC originally rendered each body. Correct for all six storable codes today.

## Findings

### Medium 1 — `complete()` / `abandon()` are unconditional; a resurrected zombie worker can overwrite or delete a taken-over key
`IdempotencyStore.complete` and `delete` key only on `(merchant_id, idempotency_key)` with no lease/owner guard (`IdempotencyStore.java:99-110`, `:113-118`). Sequence: worker A claims NEW but stalls > lease; B takes over (NEW) and completes with its response; A resumes and calls `complete()` (overwriting B's stored response) or, if A's status is non-storable, `abandon()` (deleting B's COMPLETED row entirely). Impact is bounded to a **wrong/missing replayed response** — a later retry may see a stale status or re-run through the controller. It is **not** a double-charge: `ux_payments_live_order` still blocks the duplicate live payment, so the re-run returns 409 rather than charging again.
Recommendation (hardening, not a blocker for learning scope): fence the lease. Have `takeOverExpiredLease` bump an epoch/lease token and make `complete`/`abandon` conditional (`... WHERE status='IN_PROGRESS' AND lease_until = :expectedLease` or an epoch match), so a zombie's write is a no-op. Worth at least a `learning/` note explaining why lease-based take-over without fencing is a known limitation.

### Medium 2 — `STORABLE_STATUSES` caches client-error bodies under the key for the full TTL
400/404/409/422 are stored and replayed for 24h (`IdempotencyFilter.java:45`). This is deterministic and defensible, but means a 400 validation failure is "stuck" to that key: an identical retry replays the 400 (fine), while a corrected body on the same key yields 422 MISMATCH (also fine, by design). Flagging only so the product intent is explicit — confirm that caching 4xx under the key for 24h is desired vs. only caching 201/502. No change needed if intentional.

### Low / informational
- `replay()` reconstructs Content-Type from a `status>=400` heuristic rather than storing the original content-type (`IdempotencyFilter.java:111`). Correct for all current storable codes; would silently mismatch if a future storable 2xx emitted problem+json or a 4xx emitted plain json. Consider storing content-type alongside status/body if the storable set grows.
- `MerchantContext==null` branch (`IdempotencyFilter.java:63-67`) is dead-defensive (auth runs first and rejects). Harmless; the comment already says so.
- `begin()` `row==null` after a lost claim returns `inProgress()` -> 409 (`IdempotencyService.java:52-55`). Correct conservative choice for the purge-between-claim-and-read race; retry recovers.
- Idempotency-Key length cap (<=64) matches the `VARCHAR(64)` column (`V6:13`), so a too-long key is a clean 400, never a DB truncation/insert error. Good.

### Positive (risk-calibration)
- Transaction boundaries are the crux and they are right: claim commits before the chain; provider call holds no DB connection; tx1 precedes the provider call so the unique index guards the charge. This is the hard part and it is sound.
- JdbcClient choice (vs JPA) is justified and documented; ON CONFLICT + conditional lease UPDATE are the actual arbiters, no in-memory lock. Accepted deviation, not re-litigated.
- Filter scoping is correct: exact url-pattern `/api/v1/payments` + POST recheck means reads/cancels are never idempotency-gated (`IdempotencyFilterConfig.java:27`, `IdempotencyFilter.java:58`).

## Unresolved questions
1. Is caching 4xx (400/404/422) responses under the key for the full 24h TTL intended, or should only 201/502 be storable? (Medium 2)
2. Is the zombie-worker overwrite/delete window (Medium 1) acceptable for this increment given the unique-index backstop, or should lease fencing be pulled into Phase 6?

Status: DONE
Summary: Phase 6 idempotency is correct and ships; all acceptance criteria verified and the 14 targeted tests pass. No double-charge path. Two Medium hardening notes (unfenced complete()/abandon() under lease take-over; TTL caching of 4xx), neither a blocker.
Concerns: Medium-1 lease fencing and Medium-2 storable-4xx intent need a product/scope decision.
