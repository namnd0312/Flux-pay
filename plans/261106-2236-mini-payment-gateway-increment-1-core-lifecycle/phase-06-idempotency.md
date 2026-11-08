---
phase: 6
title: "Idempotency"
status: done
priority: P1
dependencies: [5]
effort: "1 day"
---

# Phase 6: Idempotency

## Overview
Make `POST /api/v1/payments` safe to retry. A merchant-scoped `Idempotency-Key` (already covered by the phase 5 signature) stores the first response and replays it; conflicting reuse is rejected; concurrent duplicates collapse to one payment. A partial unique index on `(merchant_id, order_id)` for live payments is the business-level second guard, and the note explains why both exist and why failed orders stay retriable.

Red-team changes applied: #1 (partial unique index, failed orders retriable), #5 (filter-based storage of the real response, IN_PROGRESS lease, no `REQUIRES_NEW`), #13 (burst demo replays one pre-signed request), #15 (exercise with a known outcome).

## Requirements
- Functional:
  - `Idempotency-Key` header required on `POST /api/v1/payments` (400 `IDEMPOTENCY_KEY_MISSING`), max 64 chars.
  - Same key + same body hash + COMPLETED -> replay stored status + body, header `Idempotency-Replayed: true`. Replay never reaches the controller, so it never calls the provider.
  - Same key + different body hash -> 422 `IDEMPOTENCY_KEY_REUSED`.
  - Same key while IN_PROGRESS and lease fresh (< 60s) -> 409 `IDEMPOTENCY_IN_PROGRESS`. Lease older than 60s -> the new request takes over (the previous worker is assumed dead).
  - Stored (COMPLETED) statuses: 201, 400, 404, 409, 422, 502. Any other status (500, 503) -> row deleted so the merchant can retry with the same key.
  - Keys expire after 24h (sweeper method on the existing scheduler).
  - `orderId` duplicate for the same merchant with a different key **while a live payment exists** (CREATED, PENDING, SUCCEEDED) -> 409 `ORDER_ALREADY_EXISTS`. FAILED, EXPIRED, CANCELLED do not block a new attempt.
- Non-functional: 50 concurrent identical requests -> exactly 1 payment row, 1 provider call, 49 replays or 409s (depending on timing).

## Architecture
```
idempotency/
  IdempotencyKey (entity)       merchantId + idemKey (composite PK), requestHash (SHA-256 of rawBody), status (IN_PROGRESS|COMPLETED), leaseUntil, responseStatus, responseBody (text), createdAt, expiresAt
  IdempotencyKeyRepository      native: INSERT ... ON CONFLICT DO NOTHING RETURNING ...; native: conditional UPDATE to take over an expired lease
  IdempotencyService            @Transactional begin(merchantId, key, hash) -> NEW | REPLAY(status, body) | IN_PROGRESS | MISMATCH; complete(merchantId, key, status, body); abandon(merchantId, key); purgeExpired()
  IdempotencyFilter             OncePerRequestFilter on POST /api/v1/payments, ordered after MerchantAuthFilter.
                                begin -> REPLAY: write stored response + header; MISMATCH: 422; IN_PROGRESS: 409;
                                NEW: wrap response in ContentCachingResponseWrapper, chain.doFilter, then
                                     status in stored set -> complete(status, body); else -> abandon. copyBodyToResponse().
```
`begin()` is a plain `@Transactional` method called from a non-transactional filter: `INSERT ... ON CONFLICT DO NOTHING RETURNING` wins -> NEW. Lost -> read row: IN_PROGRESS with fresh lease -> 409; IN_PROGRESS with expired lease -> conditional `UPDATE ... WHERE lease_until < now` wins -> NEW (take-over), loses -> 409; hash differs -> 422; COMPLETED -> REPLAY. The primary key and the conditional update are the only arbiters under concurrency; no in-memory locks.

Why a filter and not controller code: error responses (502 provider down, 409 order exists) are rendered by `ProblemDetailsHandler` after the controller has lost control; only a response wrapper sees the final status and body. Teach the completed-vs-abandoned table from Requirements.

Flyway `V6__create_idempotency_keys.sql`, `V7__add_live_order_unique_index.sql`:
```sql
CREATE UNIQUE INDEX ux_payments_live_order ON payments (merchant_id, order_id)
  WHERE status IN ('CREATED','PENDING','SUCCEEDED');
```
Violation surfaces in tx1 of `create()` -> mapped to `OrderAlreadyExistsException` (409), stored by the filter.

## Related Code Files
- Create: `gateway/.../idempotency/**`, `V6`, `V7`, `docs/learning/05-idempotency.md`, `scripts/burst-replay.sh` (replays ONE pre-signed request N times in parallel: `seq 50 | xargs -P 20 -I{} curl -s -o /dev/null -w "%{http_code}\n" ... @request.json` with headers copied from Postman console; no signing in shell), Postman folder "06 Idempotency".
- Modify: `payment/PaymentOrchestrator` (map `DataIntegrityViolationException` on the live-order index -> `OrderAlreadyExistsException`), `payment/PaymentExpirySweeper` (call `purgeExpired()`), `common/ProblemDetailsHandler` (400/409/422 codes), `application.yml` (`fluxpay.idempotency.ttl: 24h`, `fluxpay.idempotency.lease: 60s`), `AbstractIntegrationTest` (signed request helper accepts the key).
- Test: `IdempotencyServiceTest` (unit, five outcomes incl. lease take-over), `IdempotencyIT` (replay with header; mismatch 422; in-progress 409; order duplicate 409 while PENDING; same orderId re-creatable after FAILED; 502 stored and replayed without a second provider call; TTL purge), `ConcurrentCreateIT` (ExecutorService 50 threads, same key + body -> 1 payment row, `CountingFakeProvider.calls() == 1`).

## Implementation Steps
1. **Failure demo first:** sign one create request in Postman (phase 5 script), copy headers + body into `request.json`, run `scripts/burst-replay.sh` against the phase 5 build (50 identical requests, 20 parallel, all within the 5-minute window). Count rows: `SELECT count(*) FROM payments WHERE order_id='ORD-1'` -> 50. Fifty pay URLs for one order; mock-provider has 50 sessions. This is the double-charge bug. Record numbers in the note.
2. Write `docs/learning/05-idempotency.md` (VN): why clients retry (timeouts, mobile networks), definition of idempotency, why `orderId` uniqueness alone is not enough (merchant retries after 502 need the *same* answer, not a 409) and why it must not block failed orders, key scope per merchant, request-hash to detect misuse, IN_PROGRESS as a lease not a lock, why the DB PK decides, what to store and which statuses, TTL, Stripe/VNPAY comparison. Demo numbers before/after.
3. Flyway V6, entity, repository natives.
4. `IdempotencyService` with the five outcomes and lease take-over.
5. `IdempotencyFilter` with `ContentCachingResponseWrapper`, ordered after `MerchantAuthFilter`.
6. Flyway V7 partial index; map violation to 409 `ORDER_ALREADY_EXISTS`.
7. `purgeExpired()` on the existing scheduler.
8. Tests listed above; `ConcurrentCreateIT` is the acceptance test for the whole increment.
9. Re-run `scripts/burst-replay.sh`: 1 payment row, 1 mock session, responses = 1x201 + mix of replays/409. Record in note.
10. Postman: create with key; repeat (replayed header); same key different amount (422); new key same orderId while PENDING (409); cancel, then new key same orderId (201); missing key (400).
11. Exercise ("Tự phá thử"): replace `INSERT ... ON CONFLICT DO NOTHING` in `begin()` with `existsBy` + `save`, re-run the burst: duplicate payments reappear. Explain why a check-then-act in application code loses to the database constraint. This is the bridge to increment 2.

## Success Criteria
- [ ] Burst demo recorded before (50 rows) and after (1 row) in note 05.
- [ ] `ConcurrentCreateIT` green: 1 payment, 1 provider call.
- [ ] 502 is replayed from storage without a provider call (asserted via `CountingFakeProvider`).
- [ ] FAILED order re-creatable with a new key (asserted).
- [ ] Unit + IT green; Postman folder passes; whole-increment acceptance list in `plan.md` satisfied.

## Risk Assessment
- Storing the full response body duplicates data -> acceptable, bounded by TTL; note mentions storing only the payment id as an alternative.
- `ContentCachingResponseWrapper` must `copyBodyToResponse()` on every path including exceptions -> use try/finally; covered by the idempotency MockMvc tests.
- Pre-signed request expires after 5 min -> script prints a reminder; re-sign in Postman if 50x401 appears.

## As-built notes (deviations)
- Idempotency persistence uses JdbcClient (`IdempotencyStore`) mirroring `WebhookEventStore`, not a JPA entity + JpaRepository: `ON CONFLICT DO NOTHING RETURNING` and the conditional lease-take-over `UPDATE ... WHERE lease_until < now RETURNING` are the arbiters, which the raw-SQL store expresses directly and matches the existing webhook pattern (no JPA composite-key entity to reshape).
- `IdempotencyServiceTest` is realized as `IdempotencyServiceIT` (the five outcomes + lease take-over + purge live in SQL, so it runs against the Testcontainers Postgres).
- An over-64-char key reuses the 400 `IDEMPOTENCY_KEY_MISSING` code (invalid key) rather than a new code.
- `create()` uses `saveAndFlush` in tx1 so the live-order unique-index violation surfaces there and maps to 409 `ORDER_ALREADY_EXISTS`.
- Test DB pool raised to 20 (`application-test.yml`) for `ConcurrentCreateIT` (50 threads).
- Review: reports/from-code-reviewer-to-cook-phase-06-idempotency-review-report.md.
