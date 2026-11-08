# Increment 1 Sync Core: Merchant HMAC Auth and Idempotency

**Date**: 2026-11-07 23:09
**Severity**: High
**Component**: gateway API (`MerchantAuthFilter`, `IdempotencyFilter`, `IdempotencyStore`), Postgres schema
**Status**: Resolved (one Medium hardening item deferred to increment 2)

## What Happened

Finished the synchronous core of increment 1: phase 5 (merchant HMAC auth) and phase 6 (idempotent `POST /api/v1/payments`). Committed on `feat/hmac-auth-and-idempotency` as `1abc1dc`. Full `./mvnw verify` is green: gateway 62 unit and 39 integration tests, including a 50-thread `ConcurrentCreateIT` that asserts exactly 1 payment row and 1 provider call; mock-provider 4 tests.

## The Brutal Truth

The phase-5 review caught a real bug I would have shipped. A merchant whose ciphertext decrypts to an empty secret made `HmacSigner` throw `IllegalArgumentException("Empty key")`, which escaped the filter as a 500. The spec says the filter never returns 500. The reason it slipped in: I trusted that "decrypt succeeded" implies "usable key," and I didn't write a test for the degenerate value. Embarrassing for a security filter.

## Technical Details

- Canonical string: `METHOD\nPATH\nX-Timestamp\nIdempotency-Key\nsha256hex(body)`, HMAC-SHA256. Binding method and path blocks cross-endpoint replay. Hashing the body blocks tampering.
- Secrets are AES-256-GCM encrypted at rest, with a random IV per secret and the api-key as AAD. Startup fails fast on a missing key and self-checks by decrypting the DEMO secret.
- Every rejection is 401; oversize bodies are 413. Never 500.
- Idempotency keys table: `INSERT ... ON CONFLICT DO NOTHING RETURNING`. The primary key is the only arbiter. IN_PROGRESS is a time-bounded lease, and a dead worker is taken over with a conditional `UPDATE`.
- Only deterministic statuses `{201,400,404,409,422,502}` are stored for replay. Others are abandoned so the client can retry.
- Partial unique index on `(merchant_id, order_id)` where live. FAILED, EXPIRED, and CANCELLED stay retriable.

## What We Tried

- Phase 5: fixed the empty-secret case in the filter, not in the shared `HmacSigner`, because the webhook path reuses the signer and I did not want to change its contract. Added a regression test.
- Phase 6: the phase-6 review shipped clean. The review traced that tx1 `saveAndFlush` runs before the provider call, so the unique index guarantees at most one charge.
- Persistence: I used `JdbcClient` (`IdempotencyStore`) instead of a JPA entity plus `JpaRepository`, mirroring `WebhookEventStore`. `ON CONFLICT` and the conditional lease `UPDATE` are the correctness-critical primitives, so they are written as explicit SQL.

## Root Cause Analysis

The phase-5 bug came from assuming a successful decrypt yields a valid key. Validation happened in the wrong layer, and the failure path was untested. The idempotency design is correct because the database constraint arbitrates the race, not an application-level check-then-act. This is the same lesson as the phase-4 webhook dedup.

## Lessons Learned

- In a security filter, every exception path must map to a 4xx. Write the test for the degenerate input (empty, null, malformed) before the happy path.
- Do not rely on `SELECT` then `INSERT` for uniqueness. Put a unique constraint in the database and let it reject the second writer.
- A lease needs fencing. A zombie worker whose lease was taken over can still overwrite the stored replay status. This is bounded: it can produce a wrong replay response, but it cannot cause a double charge, because the unique index plus tx1 ordering already guarantees at most one provider call. I deferred it with the user's approval.

## Next Steps

- Increment 2 (concurrency hardening): add lease fencing (a monotonically increasing lease token checked on the replay-status write). Owner: branch author (namnd0312). Documented in `docs/learning/05-idempotency.md`.
- Suggested (not yet planned): add a test that forces lease expiry so the takeover path is exercised. The current `ConcurrentCreateIT` covers contention, not takeover.
