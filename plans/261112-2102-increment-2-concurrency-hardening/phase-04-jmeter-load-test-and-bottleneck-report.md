---
phase: 4
title: JMeter Load Test and Bottleneck Report
status: completed
priority: P2
dependencies:
  - 1
  - 2
  - 3
effort: 2 days
---

# Phase 4: JMeter Load Test and Bottleneck Report

## Context Links
- Research: `research/researcher-02-fencing-webhooks-jmeter-report.md` (section 4), `research/researcher-01-locking-and-retry-report.md` (Q5 trade-off table)
- Red team: `reports/from-code-reviewer-to-planner-red-team-*-plan-review-report.md`
- Roadmap: `plans/reports/brainstorm-261106-2236-mini-payment-gateway-learning-roadmap-report.md` (increment 2 success metric)
- Auth contract: `README.md` "Merchant authentication" (canonical string, headers)
- Existing burst script: `scripts/burst-replay.sh`

## Overview
Build a repeatable JMeter harness, prove with numbers that phases 1-3 hold under load (before vs after), compare optimistic vs pessimistic on the webhook path, and find the first real bottleneck. Output is a measured report, not tuning.

<!-- Updated: Red Team 2026-11-12 - race scenario redesigned, invariants rewritten, R0 recipe for git-ignored files, management port, mock-provider ruled out as bottleneck first -->

## Key Insights
- The roadmap's expected bottleneck ("DB connection held during the provider call") is already gone: `PaymentOrchestrator.create` calls the provider with no transaction open. Working hypothesis to test, not assume: the first limit is Tomcat request threads blocked on the synchronous provider call, with HikariCP mostly idle.
- **Rule out the test double first.** The mock-provider's webhook pool was 2/4/50 with abort-on-full; phase 2 makes it configurable and counted. A run is valid only if `mock.webhook.rejected = 0` and `exhausted = 0`; otherwise the "bottleneck" is the mock.
- `docker-compose.yml`, `.env`, `.env.example`, `postman/` are git-ignored (kept local, user decision 2026-11-12). A tag or worktree does not contain them: the "before" run needs the explicit recipe in step 8.
- A JMeter thread runs its samplers sequentially and Synchronizing Timer groups *threads*, not samplers; stock JMeter 5.6.3 has no parallel controller. The race scenario is therefore timing-based within one thread.
- Expected non-failures must not count as errors: `409 IDEMPOTENCY_IN_PROGRESS`, `409 PAYMENT_ILLEGAL_TRANSITION` (race loser), replays.
- Load generator, gateway, mock and Postgres share one laptop: absolute numbers are not capacity figures; only relative comparisons and the shape of saturation are valid. Say so in the report.
- Carried from phase 2: the mock exposes only `health` over HTTP, so the load-test override must also expose `metrics` on the mock; counters appear only after their first increment (treat a missing series as 0). The local stack currently runs its db on host port 5433 while `docker-compose.yml` and the `increment-1` snapshot map 5432: settle the port with the user before any compose run.
- JMeter property names / CLI flags and Tomcat metric names are **[UNVERIFIED]**: check with `jmeter --help` and the live `/actuator/metrics` list when authoring.

## Requirements
- Functional:
  - Headless runner: `load-tests/run.sh <scenario> [-J overrides]` -> `jmeter -n -t ... -l results/<ts>/<scenario>.jtl -e -o results/<ts>/<scenario>-report`, then waits for quiescence and runs the invariant checks.
  - Every `/api/**` sampler is HMAC-signed per request (JSR223 Groovy PreProcessor): canonical `METHOD\nPATH\nX-Timestamp\nIdempotency-Key\nsha256hex(body)`, path without query string, hash over the exact bytes sent, hex lowercase, `X-Timestamp` epoch millis.
  - Scenarios:
    | File | What | Proves |
    |---|---|---|
    | `baseline-create-pay-poll.jmx` | create -> POST mock pay -> bounded poll until terminal; ramp 10 / 50 / 200 threads | throughput, p95/p99, first saturation point |
    | `same-key-burst.jmx` | N threads, one key + body, Synchronizing Timer (threads) | exactly 1 payment row per key under real HTTP |
    | `webhook-vs-cancel.jmx` | per thread iteration: create -> POST mock pay (mock `MOCK_CALLBACK_DELAY_MS=D`) -> timer ~D with jitter -> cancel -> poll | conflict handling; optimistic vs pessimistic |
    Fallback for the race if the mock's async send cannot be lined up: JMeter posts the signed webhook itself (`providerRef` = last path segment of `payUrl`; HMAC with the dev mock secret; header `X-Mock-Signature`).
  - `load-tests/verify-invariants.sql`, run via `docker compose exec -T db psql` (no host `psql`) **after quiescence** (no new requests, mock retry window elapsed, about 20s). No age filters. Hard checks, each must return 0 rows:
    - payments with more than one terminal row in `payment_transitions`
    - payments that have transitions whose latest `to_status` differs from `payments.status` (inner join: CREATED rows with no transition are fine)
    - `webhook_events` still `RECEIVED`
    - a `SUCCESS` event that is `REJECTED` or `RETRYABLE` while its payment (by `payment_id`, or by `merchantRef` in `raw_body`) is not SUCCEEDED and was not cancelled/expired first (scenario-specific: in `baseline` any such row is a loss; in `webhook-vs-cancel` only when the payment is still PENDING)
    - payments still PENDING for which the mock reports the session paid (baseline only)
    - `idempotency_keys` still `IN_PROGRESS`
    Reported as numbers, not pass/fail: count of `RETRYABLE` events by reason, `REJECTED` by reason, optimistic retries by path, mock counters.
- Non-functional: metrics reachable only on localhost and only for load runs.

## Architecture
```
load-tests/
  run.sh                      scenario runner (headless) + quiesce + invariants
  hmac-sign.groovy            shared JSR223 PreProcessor script
  baseline-create-pay-poll.jmx
  same-key-burst.jmx
  webhook-vs-cancel.jmx
  verify-invariants.sql
  collect-metrics.sh          polls gateway + mock metrics during a run -> CSV
  results/                    git-ignored
docker-compose.loadtest.yml   override (local, like the base compose file)
```
Override sets on the gateway `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health,metrics`, `MANAGEMENT_SERVER_PORT=9090`, publishes `127.0.0.1:9090:9090`; same idea for the mock (`127.0.0.1:9091`). The app port keeps serving only `health`. `run.sh` teardown note: `docker compose down` after a session (`restart: unless-stopped`).

Metrics sampled every 2s:
- gateway: `hikaricp.connections.active` / `.pending` / `.acquire`; `tomcat.threads.busy` vs max (may need `server.tomcat.mbeanregistry.enabled=true`; fallback: thread dump count of `http-nio` threads waiting on the provider call); `http.server.requests` by uri + status; `fluxpay.payment.optimistic.retries` by `path`
- mock: `mock.webhook.sent|retried|exhausted|rejected`, executor active/queue size, its own `http.server.requests`
- Postgres: `select count(*) from pg_stat_activity where wait_event_type = 'Lock'`

Run matrix:
| Run | Build | Knobs | Question |
|---|---|---|---|
| R0 | `increment-1-done` | `webhook-vs-cancel`, 50 threads | how many 5xx / stuck events before the fixes |
| R1 | current | same, `lock-mode=optimistic` | 5xx = 0? retries per 1000 races |
| R2 | current | same, `lock-mode=pessimistic` | retries vs lock waits, p95 delta |
| R3 | current | `baseline`, mock delay 0, ramp | where does throughput flatten |
| R4 | current | `baseline`, `MOCK_CREATE_DELAY_MS=500` then `2000` | which resource saturates first with a slow provider |
| R5 | both builds | `same-key-burst` | 1 row per key (regression check for increment 1) |

## Related Code Files
- Create: everything under `load-tests/` listed above; `docker-compose.loadtest.yml` (local)
- Create: `docs/learning/09-load-test-and-bottlenecks.md`
- Modify: `.gitignore` (`load-tests/results/`, `docker-compose.loadtest.yml`), `README.md` (increment 2 section: lock mode, webhook 503 contract, how to run load tests, note that compose files are local), `docs/system-architecture.md` (webhook retryable flow incl. lines stating "always 200", lease token, lock mode; update the stale "increment 2" forward references)
- Modify (local): `docker-compose.yml` only if a passthrough env is still missing after phase 2

## Implementation Steps
1. `jmeter --version`, `jmeter --help`; confirm flags. Create `load-tests/` skeleton and `run.sh`.
2. `hmac-sign.groovy`; validate with a 1-thread run: 201 from `POST /api/v1/payments` (401 means the canonical string is wrong: compare with `CanonicalRequest`).
3. `same-key-burst.jmx` first (simplest, known answer from `scripts/burst-replay.sh`): 1 row per key.
4. `baseline-create-pay-poll.jmx`: extract `paymentId` + `payUrl` (JSON extractor), POST the pay URL, poll `GET /api/v1/payments/{id}` (bounded; size the poll budget above the mock retry window when the slow-provider knob is on); assertion on terminal status.
5. `webhook-vs-cancel.jmx` as specified (single thread: pay, timed wait, cancel). Response assertions accept 200 and 409 for cancel. Check with a 1-thread dry run that both outcomes (webhook wins / cancel wins) occur across iterations; if one side always wins, tune the timer jitter or switch to the JMeter-signed webhook fallback.
6. `collect-metrics.sh` + `docker-compose.loadtest.yml`; list available metric names once and settle the [UNVERIFIED] Tomcat item.
7. `verify-invariants.sql` + quiesce step in `run.sh`; a violated hard check fails the run loudly. Sanity-check the SQL against a hand-made bad row (e.g. an event left `RECEIVED`) so the checks are known to fire.
8. **R0 recipe (old build):**
   1. `docker compose down` for the current stack (both stacks bind 8080 / 8081 / 5432).
   2. `git worktree add ../flux-pay-increment-1 increment-1-done`.
   3. Copy into the worktree: `docker-compose.increment-1.yml` (snapshot from phase 1) as `docker-compose.yml`, and `.env`.
   4. `docker compose -p fluxpay-inc1 up --build` there (separate project name = separate DB volume).
   5. Run `webhook-vs-cancel` and `same-key-burst` from the current working copy's `load-tests/` against it. The old mock has no retry/pool knobs and the old gateway has no metrics override: collect JMeter results + invariant SQL only.
   6. `docker compose -p fluxpay-inc1 down -v`; remove the worktree.
9. R1-R5 on the current build. Reset the DB volume between runs. Discard any run where the mock counters show `rejected > 0` or `exhausted > 0`, fix the knob, rerun.
10. Write `docs/learning/09-load-test-and-bottlenecks.md` (VN): how to read a JMeter report (throughput, percentiles, error %), closed vs open workload caveat, the run matrix with tables of real numbers, before/after for the race, optimistic vs pessimistic verdict with data, the first bottleneck with the metric that shows it (and the evidence that it is not the mock), what was NOT measured, and what increment 3 (async/Kafka) should change. State plainly if the hypothesis in Key Insights was wrong.
11. Decide with the user, from R1/R2 data, whether the lock-mode toggle stays or the losing arm is removed (follow-up, not done silently here).
12. Update `README.md` and `docs/system-architecture.md`.

## Todo List
- [x] Skeleton + runner + HMAC script verified
- [x] 3 scenarios
- [x] Metrics collection (gateway + mock) + invariant SQL proven to fire
- [x] R0 (old build) recorded
- [x] R1-R5 recorded, mock ruled out
- [x] Note 09
- [x] README + architecture doc

## Success Criteria
- [x] `load-tests/run.sh <scenario>` works headless from this working copy with the local compose stack running; HTML report generated.
- [x] Current build: all hard invariant checks return 0 rows after every scenario; 5xx count = 0 in R1, R2, R5.
- [x] R0 vs R1 before/after numbers recorded (roadmap metric: conflicts/duplicates > 0 before, = 0 after). If R0 shows 0 failures at this load, say so and raise the load or record that the race was not reproduced over HTTP (the ITs remain the proof).
- [x] Optimistic vs pessimistic comparison table with retries, lock waits, p95.
- [x] First bottleneck named with the metric evidence, and mock counters showing the mock was not the limiter.

## As-built notes (deviations)
- Results (2026-11-13), full tables in `docs/learning/09-load-test-and-bottlenecks.md`:
  - R0 (increment-1 build, 900 races): 97 cancel 500s, 143 events stuck `RECEIVED`. R1/R2 (new build, both lock modes): 0 / 0, all HARD invariants hold.
  - Optimistic vs pessimistic (1000 races, 50 threads): 204 vs 136 retries, webhook-path 120 vs 0; latency difference within noise. No performance winner.
  - First bottleneck (re-measured 2026-11-15 on the final build): fast provider -> the queue forms at the HikariCP pool (10/10, 160 pending at 200 threads; throughput flattens 307 -> 351/s, the earlier "fell 300 -> 254" did not reproduce); pool as cause unconfirmed. Slow provider (2000 ms) -> Tomcat request threads: 200 -> 400 threads gives 91 -> 95 payments/s, create p95 2656 ms, poll p50 3 -> 400 ms with Hikari pending 4. The mock's Tomcat is also 200/200 in that run (not separated). The plan's hypothesis was wrong for the fast-provider case.
- "Before" run used the still-running increment-1 images (tagged `:increment-1`) instead of a git worktree build: same code, built from `main` before this branch existed.
- First R0 attempt at 50 threads was invalid: the old mock returned 500 for 748/1000 pay calls (sender pool full). Re-run at 3 threads.
- `.jmx` files were generated by a script, not authored in the JMeter GUI; each was validated by running it headless.
- `docker-compose.loadtest.yml` lives in `load-tests/` and is tracked (no secrets; the base compose file stays local).
- Invariant `payments_still_pending` was replaced by `pending_payments_that_have_a_webhook_event`: time-boxed runs legitimately leave created-but-unpaid payments.
- Each configuration ran once (plan said 3 runs, median). Lock waits were not observable with 2 s sampling.
- End-to-end early-webhook demo with the real mock (`auto-pay`) done here: 16/16 first attempts answered "retry", second attempt delivered, all SUCCEEDED.
- Lock-mode toggle (step 11): user decided 2026-11-15 to remove the optimistic arm. `fluxpay.webhook.lock-mode`, `FLUXPAY_WEBHOOK_LOCK_MODE` and `WebhookVsCancelOptimisticModeRaceIT` are gone; the webhook path always takes the row lock.
- Harness review fixes (2026-11-15): `run.sh` verdict is fail-closed (failed JTL samples, "created > 0" guard, HARD row count, mock drop counters); metric read failures are empty cells, not 0, and the collector exits if the endpoints are unreachable; failed create starts the next loop (no `/NONE` follow-ups); poll result is whitelisted to terminal statuses; burst asserts one fresh 201 per key and `IDEMPOTENCY_IN_PROGRESS` bodies; `webhook_events_left_retryable` is HARD; duplicate-order check counts live rows only; signature headers no longer reach the mock. Proven by a wrong-port control (FAILED) and a per-thread-key burst (57/60 flagged).
- After the fixes, R1 (3 and 50 threads), R3, R4 (500 ms, 2000 ms at 200 and 400 threads) and R5 were re-run on the final build: all PASSED. R0 and the optimistic/pessimistic comparison keep their 2026-11-13 numbers.
- The `.jmx` generator script was not kept; future changes are edits of the saved XML (validated by a headless run).

## Risk Assessment
- Hand-written `.jmx` XML is error-prone -> author in the JMeter GUI, commit the saved file, run headless only.
- Timing-based race may be lopsided -> step 5 dry run + fallback.
- Signed request window is 5 min: timestamps are generated per request in the PreProcessor, so long runs are fine.
- Laptop noise -> repeat each run 3 times, report median; compare shapes, not absolutes.
- R0 compose snapshot missing (phase 1 step skipped) -> recover it from the pre-phase-2 state by hand and say so in the report; "before" is then approximate.

## Security Considerations
- Metrics on a separate management port bound to `127.0.0.1`; never in the default compose or `application.yml`.
- `load-tests/` holds only the dev merchant secret and dev mock secret already in the repo; no real credentials. `results/` git-ignored. The `.env` copy for R0 lives in a sibling worktree and is deleted in step 8.6.

## Next Steps
- Close the increment: update plan status, journal entry, memory note; inputs for increment 3 (Kafka inbox for `RECEIVED`/`RETRYABLE` events, async provider call if the bottleneck confirms it).
