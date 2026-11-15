# Phase 4 fix round review (harness fixes + lock-mode removal)

## Scope
- Diff: `WebhookPaymentApplier.java`, `application.yml`, `WebhookVsCancelRaceIT.java`, `WebhookFallbackDecisionTest.java`, deleted `WebhookVsCancelOptimisticModeRaceIT.java`; `load-tests/*` (whole); `README.md`, `docs/system-architecture.md`, notes 07 + 09; plan files.
- Empirical: `/bin/bash` 3.2.57 `-n` on both scripts; the `$(python3 - <<'PY' ... PY)` + empty-array + `${#F[@]}` + `grep -c` no-match constructs under `set -euo pipefail` (all OK / fail-closed); summary script over `results/20261115-*` and the two 11-13 comparison runs; per-label JTL counts for the 11-13 3-thread runs; the per-thread-key burst control in the scratchpad (`neg-burst/neg.jtl`).
- Not run: JMeter, docker, mvnw (stated as passed).

## Finding status

| Prior | Status | Evidence |
|---|---|---|
| H1 | Fixed | `run.sh:89-117`: failed JTL samples, created>0 guard, HARD row count, mock drops. NEG-wrong-port run -> FAILED (matrix.log:282-283) |
| M1 | Fixed | `same-key-burst.jmx:77-103`; control: 57/60 flagged `unexpected 409 ... live payment already exists` |
| M2 | Fixed | `on_sample_error=startnextloop` in baseline + race |
| M3 | Fixed | whitelist in final sampler; non-200 poll also fails -> next loop |
| M4 | Fixed (one leftover, L-B) | `collect-metrics.sh:33-52` |
| M5 | Fixed | `verify-invariants.sql:42-43` |
| M6 | Fixed | 400-thread run; numbers match (see below) |
| M7 | Fixed | 09 sec 4.1 reworded; arch doc sec 15 says "unconfirmed" |
| M8 | Verified real | both 11-13 3-thread JTLs: cancel 200=495 / 409=405 |
| L1, L2, L9, L11 | Fixed | |
| L10 | **Fixed backwards** | see L-A |
| L3-L8 | Fixed | README 3 attempts/2 retries, CONCURRENT_UPDATE, health on 9090; exercise 2 tells to edit override; exercise 1 open question; params under tables |

## Defects

### Medium

**M-A. Race IT lost its only "the race actually happened" guard** — `gateway/src/test/java/com/fluxpay/gateway/payment/WebhookVsCancelRaceIT.java:135-139`
- Deleted subclass asserted retries > 0 (proof that writers collided). Remaining assert is `webhook retries == 0`, which a non-racing test also satisfies.
- Scenario: a later change breaks the barrier or serialises the two submits (e.g. pool of 1, cancel issued after webhook returns) -> every payment ends SUCCEEDED, no conflict ever happens, test stays green and still "proves" the row lock.
- Fix: assert both sides won at least once (`webhookWins > 0 && webhookWins < PAYMENTS`) and/or `cancel`-path retries > 0 over the 200 races (load run shows 36/900 at 3 threads, so it fires).

### Low

**L-A. Latest-transition tie-break is inverted; comment states the wrong order** — `load-tests/verify-invariants.sql:19-21`
- Chain is `null->CREATED`, `CREATED->PENDING`, `PENDING->terminal`. The row leaving PENDING is the LATER step. `(from_status='PENDING') asc` sorts false first, so on a `CREATED->PENDING` / `PENDING->SUCCEEDED` tie DISTINCT ON keeps `CREATED->PENDING` -> `payment_status_differs_from_latest_transition` = 1 false positive. Before the "fix" the pick was arbitrary; now it is deterministically wrong.
- Fail-closed and practically unreachable (separate txs, microsecond app clock), but the comment teaches the wrong ordering.
- Fix: `order by payment_id, created_at desc, (from_status = 'PENDING') desc nulls last` (true first; NULL of the first row last, so `null->CREATED` vs `CREATED->PENDING` also resolves correctly). Comment: "the one leaving PENDING is the later step, so it sorts first".

**L-B. `pg_lock_waiters` still records 0 on failure** — `load-tests/collect-metrics.sh:60`
- `int(stdout.strip() or 0)`: `docker compose exec` failing (db restarted, wrong `-p`) gives empty stdout -> 0, exactly the M4 shape. Not gating, and note 09 warns not to read the column, so low. Fix: `return "" if r.returncode else int(r.stdout.strip())`.

**L-C. Mock drop gate reads lifetime counters, not per-run** — `run.sh:101-110`
- `mock_rejected`/`mock_exhausted` are cumulative since mock start; the matrix runs several scenarios per stack. A drop in run N fails runs N+1.. (false FAIL, not fail-open). Use last - first row, or restart the mock per run.

**L-D. Note 09 claims not fully backed by stored data**
- `09-...md:83` webhook-path retries "120" (11-13 optimistic): 11-13 `metrics.csv` has only `optimistic_retries` total (204); no per-path column existed then. Source not recorded. This is the decisive number for the removal; say where it came from or mark it.
- `09-...md:134` "Pool còn rảnh": in the 400-thread run Hikari active peaked 10/10; only pending was low (4). Say "pool hardly queued (pending 4)".
- `09-...md:97` "hàng đợi ở gateway làm webhook tới muộn hơn": 54% vs 48% cancel wins is stated as a cause; not measured (mock sender pool queueing is the other candidate, prior L12). Mark as hypothesis.
- Optional strengthening for `:138`: pay p95 4 ms / p99 16 ms in the 400-thread run shows the mock was not queueing pays despite busy 200/200.

**L-E. Scope / status drift in docs and plans**
- `plans/261106-...-increment-1-core-lifecycle/plan.md`: unrelated 11-12 compose/newman verification ticked in this change set. Commit separately.
- `docs/system-architecture.md:248,267` say increment 2 done "2026-11-13"; phase 4 is `in-progress` and the removal is 11-15.

## (b) Remaining lock-mode references
`git grep` (code, tests, README, docs, ignored `docker-compose.yml`): none outside the clearly historical lines (note 07:103, note 09 sec 3, arch doc:286). `PaymentRepository` hits are `LockModeType` (JPA). `loadForSweep` still used by fallback read and sweeper; test stub kept correctly.

## (c) Public contracts
- Only intended change: `fluxpay.webhook.lock-mode` / `FLUXPAY_WEBHOOK_LOCK_MODE` removed. Default was `pessimistic`, so default behaviour is unchanged. A deployment that still sets `optimistic` is silently ignored (Spring ignores unknown keys) and gets the row lock; acceptable for a local learning repo.
- `WebhookPaymentApplier` constructor change is package-private. No HTTP, schema, metric-name or error-code change.

## (d) Note 09 vs data (20261115-*)
All table cells in sec 2 (final column), sec 3 last paragraph, 4.1 and 4.2 match the summary output exactly (465/435, retry 36/0, p95 cancel 11; 458/542, 70/0, 55, 108, 12; 78/311/19/2/2/0/7, 307/1226/82/6/28/36, 351/1758/300/71/160/170; 221/602/896/52/103/200/173, 91/2021/2084/3/0/193/192, 95/2208/2656/400/4/200/200). 14% / x3.7 / x12 arithmetic correct. 11-13 sec 3 cells checked: retries 204/136, p95 cancel 118/123, create 273/246, pending 27/21 match. M6 conclusion now supported: 200 threads / 2.0 s = 100/s ceiling vs 95 measured, poll (no provider call) p50 3 -> 400 ms.
- First 11-15 burst run (133532) failed 380 samples with empty failure messages (earlier assertion version); the doc'd run is the re-run 134508 on the default stack (no metrics). Fine, but the doc does not say the first attempt failed.

## (e) JSR223Assertion in same-key-burst.jmx
- XML escaping valid (`&#x27;`, `&amp;&amp;`, `&gt;`). Bindings `prev`, `vars`, `props`, `AssertionResult` correct for JSR223Assertion.
- Order correct: ResponseAssertion with `assume_success` first, JSR223 second. Reversed order would let `assume_success` reset a JSR223 failure; current order keeps it.
- Closure -> `Function`/`BiFunction` coercion on `Properties.computeIfAbsent` / `ConcurrentHashMap.merge` works (exercised by the control run). Map is JVM-global per JMeter run; key includes runId + iteration, so no cross-run bleed.
- Gap (by design): does not require at least one fresh 201 per key; an owner 5xx is flagged as "unexpected", so no hole.

## Unresolved questions
1. Source of the 11-13 webhook-path retry split (120 / 0)?
2. Should the race IT require both sides to win at least once (M-A), or is cancel-path retries > 0 preferred?

Status: DONE_WITH_CONCERNS
Summary: All listed findings are fixed or honestly documented, harness is fail-closed on bash 3.2, lock-mode removal is clean with no stale references, and note 09 numbers match the 11-15 data. Remaining: the race IT no longer proves a race happened (M-A), and the L10 tie-break was implemented backwards (L-A).
