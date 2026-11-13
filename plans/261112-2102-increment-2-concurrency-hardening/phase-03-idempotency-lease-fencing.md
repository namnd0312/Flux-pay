---
phase: 3
title: Idempotency Lease Fencing
status: completed
priority: P2
dependencies: []
effort: 1 day
---

# Phase 3: Idempotency Lease Fencing

## Context Links
- Research: `research/researcher-02-fencing-webhooks-jmeter-report.md` (section 1)
- Code: `gateway/.../idempotency/{IdempotencyStore,IdempotencyService,IdempotencyFilter,IdempotencyOutcome}.java`, `db/migration/V6__create_idempotency_keys.sql`
- Carry-in: `docs/learning/05-idempotency.md` ("Giới hạn đã biết"), `docs/journals/2026-11-07-increment-1-auth-and-idempotency.md` (decision: monotonically increasing lease token)

## Overview
A lease that only expires is not safe: worker A stalls past 60s (GC pause, slow I/O, not dead), worker B takes the lease over, then A wakes up and writes. Today `complete()` (UPDATE) and `abandon()` (DELETE) match on `(merchant_id, idempotency_key)` only, so the zombie A overwrites B's stored response or deletes B's row. This phase adds a fencing token (`lease_epoch`) that every owner write must present.

<!-- Updated: Red Team 2026-11-12 - token comes from a sequence (in-row counter restarted at 1 after delete: ABA) -->

## Key Insights
- Never a double charge today (`ux_payments_live_order` backstops), but the stored replay can be wrong, and a zombie `abandon()` deletes a live or completed key so a third request runs again.
- Fencing token = number that increases on every acquisition and is checked on every write (Kleppmann). Here the DB row is both the lock and the storage, so the check is one extra predicate.
- The fence guarantees "the stored response belongs to the current lease owner". It does not pick the "best" response: see the trade-off in Risk Assessment.
- The token must outlive the row. `abandon` deletes the row (and so does the TTL purge); a counter stored in the row would restart at 1 on the next claim, and a zombie holding the old "1" would match a stranger's lease (ABA). So the token comes from a **Postgres sequence**: still "a number that only goes up", unique across row lifetimes.
- Demo is deterministic with `MutableClock`, no threads needed at service level.

## Requirements
- Functional:
  - `claim` and `takeOverExpiredLease` both set `lease_epoch = nextval('idempotency_lease_epoch_seq')` and return it to the caller.
  - `complete` and `abandon` succeed only if `lease_epoch = :mine AND status = 'IN_PROGRESS'`; they report whether a row was affected.
  - Zombie (0 rows affected): log WARN (merchant, key, my epoch), store nothing, delete nothing, and send its own computed response to its own client unchanged.
  - Replay, mismatch, in-progress behaviour unchanged.
- Non-functional: no new table; one additive migration (sequence + column); existing rows get a value from the column default.

## Architecture
```sql
-- V8__add_idempotency_lease_epoch.sql
CREATE SEQUENCE idempotency_lease_epoch_seq;
ALTER TABLE idempotency_keys
    ADD COLUMN lease_epoch BIGINT NOT NULL DEFAULT nextval('idempotency_lease_epoch_seq');
```
```
IdempotencyStore
  OptionalLong claim(...)                      insert (column default = nextval) ... on conflict do nothing returning lease_epoch
  OptionalLong takeOverExpiredLease(...)       update set lease_until=:l, lease_epoch = nextval('idempotency_lease_epoch_seq') where ... returning lease_epoch
  boolean complete(m, k, epoch, status, body)  ... and lease_epoch = :e and status = 'IN_PROGRESS'
  boolean deleteOwned(m, k, epoch)             ... and lease_epoch = :e and status = 'IN_PROGRESS'   (replaces delete)
IdempotencyOutcome   + long leaseEpoch; ofNew(long epoch) (NEW is no longer a shared constant; IN_PROGRESS, MISMATCH and replay() pass 0)
IdempotencyService   complete(m, k, epoch, status, body) -> boolean; abandon(m, k, epoch) -> boolean
IdempotencyFilter    runAndStore carries outcome.leaseEpoch(); false -> WARN, respond as computed
```
Signature change, all callers (verified by grep):
- `IdempotencyFilter.java:77` (`begin`), `:97` (`complete`), `:103` (`abandon`)
- `IdempotencyServiceIT.java:59` (`complete`)
- `IdempotencyService.java:47,68,76,81` (store calls)
- `begin` callers in `IdempotencyServiceIT` / `IdempotencyIT.java:97` compile unchanged (return type same).

## Related Code Files
- Create: `gateway/src/main/resources/db/migration/V8__add_idempotency_lease_epoch.sql`
- Create: `gateway/src/test/java/com/fluxpay/gateway/idempotency/LeaseFencingIT.java`
- Create: `docs/learning/08-lease-fencing.md`
- Modify: `IdempotencyStore.java`, `IdempotencyService.java`, `IdempotencyOutcome.java`, `IdempotencyFilter.java`, `IdempotencyServiceIT.java`
- Modify: `docs/learning/05-idempotency.md` (replace the "known limit" paragraph with a pointer to note 08)

## Implementation Steps
1. **Failure demo (service level, deterministic):** `LeaseFencingIT` against current code.
   - Overwrite: A `begin` -> NEW. Clock +61s. B `begin` -> NEW (take-over). B `complete(201, bodyB)`. A `complete(502, bodyA)`. `begin` again -> replays **502**: wrong, B's success is gone.
   - Delete: same up to B complete; A `abandon`. `begin` again -> **NEW**: the request runs a third time.
   - Re-claim (the case an in-row counter would miss): A `begin`; clock +61s; B takes over; B `abandon`; C `begin` -> NEW (fresh row); A `complete` / A `abandon` must not touch C's row.
   Record both in the note.
2. Write `docs/learning/08-lease-fencing.md` (VN): lease vs lock, why "expired" does not mean "dead" (GC pause, clock, network), zombie writer, fencing token, why the check must live in the same statement as the write (conditional UPDATE, not select-then-update), why the token must outlive the row (ABA after delete; sequence vs in-row counter vs random owner id), comparison with Stripe-style `locked_at`, what the fence does not promise.
3. Migration V8. `ddl-auto: validate` is unaffected (no JPA entity for this table).
4. Store + outcome + service changes.
5. Filter: pass the epoch; on `false` log WARN and continue to `copyBodyToResponse()` (keep the existing try/finally shape; `abandon` in `finally` also passes the epoch).
6. Update `IdempotencyServiceIT` call; turn step 1 into assertions: zombie `complete` returns false and replay stays 201/bodyB; zombie `abandon` returns false and the row survives; in the re-claim case A's token differs from C's, both A writes affect 0 rows and C's lease is intact.
7. Filter-level IT (reuse the `CountingFakeProvider` latch from phase 2; if phase 2 is not done yet, add the latch here): request A parked in the provider, clock +61s, request B runs to completion, release A. Assert: stored replay equals B's response; A's client still receives A's computed response; exactly one live payment row; WARN logged.
8. `./mvnw verify`. "Tự phá thử": drop `and lease_epoch = :e` from `complete`, rerun `LeaseFencingIT`.

## Todo List
- [x] Demo recorded (overwrite + delete)
- [x] Note 08, note 05 pointer
- [x] V8 migration
- [x] Store / outcome / service / filter
- [x] `LeaseFencingIT` + filter-level IT green
- [x] `./mvnw verify` green

## Success Criteria
- [x] Zombie `complete` and `abandon` affect 0 rows, including after delete-then-re-claim; stored response is the current owner's.
- [x] `ConcurrentCreateIT` still green (1 payment, 1 provider call).
- [x] Before/after recorded in note 08.
- [x] Full `./mvnw verify` green.

## As-built notes
- Demo before the fix: late `complete(502)` replaced the stored 201; late `abandon` deleted the completed key (next `begin` = NEW).
- `IdempotencyFilter` uses a `settled` flag so a refused `complete` is not followed by a pointless `abandon`; lost lease logs one WARN.
- Filter-level IT asserts the WARN via `OutputCaptureExtension`.
- Review (`reports/from-code-reviewer-to-cook-phase-03-lease-fencing-review-report.md`, no Critical/High): added the "zombie writes while the new owner is still IN_PROGRESS" test; sequence `OWNED BY` the column; `abandon` failing in `finally` no longer masks the response (pre-existing bug); a failing `complete` now leaves the key leased until expiry instead of releasing it (intended, documented in note 08).

## Risk Assessment
<!-- Updated: Validation Session 1 - zombie returns its own response; 409 replay accepted and documented -->
- **Trade-off (confirmed by user 2026-11-12: accept and document, no change to the 409 body):** in the filter-level scenario A already inserted the payment in tx1, so B's create hits `ux_payments_live_order` and B stores `409 ORDER_ALREADY_EXISTS`. With the fence, that 409 is what the merchant replays, while A's `201` (with `payUrl`) reaches only A's original, probably timed-out connection. Correct and deterministic, but the merchant cannot learn the payment id from the replay. Note 08 must explain this case explicitly.
- Lease renewal / heartbeat for long requests: not needed (provider timeouts 2s + 5s vs 60s lease). Out of scope.
- TTL purge racing an in-flight request: 24h TTL vs 60s lease; and with a sequence token a purge + re-claim can no longer be matched by an old owner.
- Rolling deploy with old code writing unfenced: single instance, learning project; note it in the doc as a real-world concern.

## Security Considerations
- No new input. Epoch never leaves the server (not in headers or bodies).
- WARN log contains merchant id + key + epoch only, no body.

## Next Steps
- Independent of phases 1-2 in code (different package); can be done in any order before phase 4.
