# Phase 4 load-test harness review

## Scope
- Files: `load-tests/{run.sh,collect-metrics.sh,hmac-sign.groovy,verify-invariants.sql,docker-compose.loadtest.yml,*.jmx}`, `docs/learning/09-load-test-and-bottlenecks.md`, `README.md`, `docs/system-architecture.md`, `.gitignore`
- Method: static read against gateway + mock-provider source on this branch. Nothing run against the stack, no JMeter, `results/` and `target/` not read.
- Empirical checks done: `bash -n` on both scripts; `${EXTRA[@]+...}`, `COMPOSE+=`, `${LABEL:+..}`, `stop_metrics` under `/bin/bash` 3.2.57 with `set -euo pipefail` (all OK); awk verdict on an empty file; JMeter `HeaderManager` add/recover semantics in-JVM against the 5.6.3 jars (library call only).
- Not verified: the measured numbers themselves (results dir off-limits), Hibernate's `FOR NO KEY UPDATE` emission, Spring Boot 4 management-port behaviour (read from config only; consistent with the reported `tomcat max = 200`).

## Overall
HMAC signing matches the contract. Scripts are bash 3.2 clean. Main weakness: the harness verdict is fail-open (green without proof the run exercised anything), one scenario cannot detect the regression it is named for, and two doc conclusions are stronger than the data.

## Real defects

### High

**H1. `run.sh` reports "All HARD invariants hold" / exit 0 for a run that never exercised the system** — `load-tests/run.sh:42-62`
- Verdict = SQL only. JMeter exits 0 when every sample fails. Invariants are counts over the whole DB, all 0 on an untouched DB.
- Scenario: Docker Desktop VM clock drifts > 5 min after host sleep -> every `/api` call 401 `AUTH_STALE_TIMESTAMP` (or gateway down / wrong `-Jport`) -> no rows written -> green. Same for baseline when the mock drops webhooks (`mock.webhook.rejected`): payment PENDING with no event row, check 7 does not fire, only JMeter's "payment reached a terminal status" sample fails, which nothing reads.
- Fix: after JMeter, count failed samples in `results.jtl` (`success` column) and fail (or at least print the count next to the verdict); add a guard row that this run created > 0 payments; when `METRICS=1`, fail on final `mock_rejected`/`mock_exhausted` > 0.

### Medium

**M1. `same-key-burst` cannot detect broken idempotency** — `load-tests/same-key-burst.jmx:72`, `verify-invariants.sql:45-46`
- All threads send same key AND same `orderId`. `ux_payments_live_order` (V7) alone guarantees one live row per order; losers would get `409 ORDER_ALREADY_EXISTS`, accepted by `201|409`. Idempotency filter removed -> scenario still green.
- Doc line 124 "380 x 409 IDEMPOTENCY_IN_PROGRESS" is asserted nowhere in the harness.
- Fix: assert on the body for non-201 (`IDEMPOTENCY_IN_PROGRESS`) or `Idempotency-Replayed` header; or HARD check `idempotency_keys` COMPLETED count = distinct keys and exactly one 201 without the replay header per key. Alternative: count mock sessions.

**M2. Failed create produces junk follow-up requests that count as 5xx** — `baseline-create-pay-poll.jmx:81,89,111`, `webhook-vs-cancel.jmx:81,89,108,133`
- Non-201 create -> `paymentId`/`payUrl` = `NONE`. Pay goes to the gateway as `POST /NONE?outcome=SUCCESS`; cancel/poll hit `/api/v1/payments/NONE[/cancel]`. `@PathVariable UUID` mismatch and unknown path fall into `ProblemDetailsHandler.onUnexpected` -> **500**. One failed create = 3-4 extra 5xx in the report; "cancel 5xx = 0" style numbers become unreadable as soon as any create fails.
- Fix: `ThreadGroup.on_sample_error` = `startnextloop` (expected 409s are already success via `assume_success`), or an IfController on `"${paymentId}" != "NONE"`.

**M3. Poll error bodies terminate the loop and pass the terminal-status check** — `baseline-create-pay-poll.jmx:125,138-143`, `webhook-vs-cancel.jmx:147,160-165`
- Problem-detail JSON has a numeric `status` field. On any non-200 poll, `$.status` extracts `500`/`404`/`401`; loop exits; final sampler treats `"500"` as terminal -> success.
- Fix: whitelist in the final sampler (`SUCCEEDED|FAILED|EXPIRED|CANCELLED`), or extract only when response code is 200.

**M4. Metric read failures recorded as 0; the "mock was not the limiter" gate is vacuous** — `load-tests/collect-metrics.sh:28-36,43-46`
- `except Exception: pass; return 0` covers connection refused, timeout, and legit 404-before-first-increment alike. Override not loaded / port unreachable -> CSV of zeros -> `mock_rejected = 0`, `mock_exhausted = 0`, `hikari_pending = 0` look like a valid clean run.
- Fix: 0 only for HTTP 404; empty cell otherwise; probe `/actuator/metrics` once at start and exit non-zero if unreachable (run.sh should then notice the dead PID).

**M5. `RETRYABLE` after quiescence is not HARD** — `verify-invariants.sql:35-42,63`
- Only reported as INFO. After the retry window a RETRYABLE row = provider gave up or quiescence too short; either invalidates the run. Check 4 catches it only for SUCCESS events whose payment is not SUCCEEDED/CANCELLED; check 7 only when PENDING. Missed: RETRYABLE (`CONCURRENT_UPDATE`/`LOCK_TIMEOUT`) on a CANCELLED payment in `webhook-vs-cancel`, and without `METRICS=1` (burst, R0) nobody sees `mock.webhook.exhausted`.
- Fix: add `HARD | webhook_events_left_retryable`. Plan listed it as a number, so this is a recommendation to tighten, not a plan violation.

**M6. Doc conclusion 2 (slow provider -> Tomcat threads are the bottleneck) not shown by the data** — `docs/learning/09-load-test-and-bottlenecks.md:105-118`
- 200 JMeter threads vs `tomcat max` 200 in a closed workload: busy ~200 is true by construction (clients + up to 32 mock webhook senders). No evidence of requests waiting for a thread: p50 create 2017 ms at 2000 ms delay = provider delay, zero queueing. The doc's own caveat (line 120) says throughput = client threads / latency.
- What is supported: "threads are held for the provider call; with more clients than threads this would queue". To show it: threads > 200 (e.g. 400) or open workload, and create latency above provider delay.

**M7. Doc conclusion 1 and "throughput fell" overstated** — `docs/learning/09-load-test-and-bottlenecks.md:95-103,117`, `docs/system-architecture.md` section 15, phase file as-built
- 10- and 50-thread points are bound by the generator, not the system: iteration = create + pay + 100 ms poll timer; 10/0.133 s = 75/s, 50/0.167 s = 300/s, exactly the table. So "300 -> 254" compares offered load with an overloaded point, single 30 s run including ramp-up.
- "Payments created / 30 s" is not load-invariant: slower webhook = more polls per payment = more DB work per payment. The drop can be the test's own feedback loop.
- "Five borrows per create, pool 10" is correct for create (auth lookup, `begin`, tx1, tx2, `complete`) but the same loop also borrows 3 per poll (auth, `get`, `transitionsOf`, each outside a tx) and >= 4 per webhook (insert, find by providerRef, apply tx, mark). >= 12 borrows per iteration; create is under half.
- Hikari pending 176 does show the queue forms at the pool. "Pool is the cause" needs the pool-size run (admitted in section 6, but section 4 line 101 and the architecture doc state it as fact).
- Fix: reword to "first saturated resource / where the queue forms"; report total requests/s beside payments/s; say the pool attribution is unconfirmed.

**M8. Identical 405 / 495 split in both increment-2 columns** — `docs/learning/09-load-test-and-bottlenecks.md:60`
- Two independent timing-based runs of 900 landing on the same split: ~2% chance. Looks like a copy. Recheck against the two result dirs.

### Low

- **L1. Verdict passes on an empty invariants file** — `run.sh:58`. Verified: awk exits 1 on empty input -> "All HARD invariants hold". Today unreachable (psql error -> `set -e` aborts first), but fail-open. Fix: also require the HARD row count = 7.
- **L2. Signature headers leak to the mock pay request** — `hmac-sign.groovy:36-38` returns before the removal at `:64`. Verified in-JVM: headers added to the shared per-thread `HeaderManager` survive `recoverRunningVersion`. Pay request carries the create's `X-Merchant-Id`/`X-Timestamp`/`X-Signature`/`Idempotency-Key`. Harmless to the mock; wrong habit for a learning repo (merchant signature sent to a third party). Fix: move the removal above the `/api/` check. Cancel and poll are clean (removal runs for every `/api/` sampler).
- **L3. README "retried up to 3 times"** — `README.md:106`; `docs/system-architecture.md:271`. `MAX_ATTEMPTS = 3` = 2 retries.
- **L4. README 503 reasons omit `CONCURRENT_UPDATE`** — `README.md:97-98` (code: `WebhookPaymentApplier:117`). Architecture doc line 284 says the row is `RETRYABLE` for `EVENT_IN_PROGRESS`; it is not (row stays owned by the other delivery).
- **L5. Health curl in README breaks under the override** — `README.md:36-37` vs `load-tests/docker-compose.loadtest.yml:6-7`. Override comment is right; README load-test section does not say health moves to 9090/9091.
- **L6. Exercise 2 does not work as written** — `09-...md:144`. `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` is not passed through by the compose `environment:` list; must be added to the override.
- **L7. Exercise 1 premise** — `09-...md:143`. With `MAX_ATTEMPTS = 1` on this branch: cancel -> `503 CONCURRENT_UPDATE`, webhook -> RETRYABLE then redelivered. Likely no HARD invariant breaks. If that is the intended answer, fine; the question presumes one does.
- **L8. Reproducibility of the tables** — `09-...md:21,28`. Section 0 command omits `MOCK_CALLBACK_DELAY_MS=100` (delay 0 = webhook always first, no race). Doc says cancel after 85-115 ms; jmx defaults are 60 + U(0,80) (`webhook-vs-cancel.jmx:121-122`). Exact `-J` values per table not recorded. Per-path retry counts are not produced by the harness (`collect-metrics.sh` sums all paths).
- **L9. `orders_with_more_than_one_payment` false positive** — `verify-invariants.sql:45-46`. Burst owner gets 500/503 -> key abandoned, payment FAILED -> a later thread legitimately creates a second row for the order. Only under provider/internal failure. Fix: count live rows only, or exclude FAILED.
- **L10. `distinct on ... order by created_at desc` tie** — `verify-invariants.sql:16-20`. Same-microsecond transitions for one payment -> arbitrary pick -> possible false positive. Practically unreachable (second writer reads after first commit, single clock). Fix: tiebreak `, (from_status = 'PENDING') desc`.
- **L11. Invariants are whole-DB** — a previous run's leftovers fail or pad the current run. Plan says reset the volume; `run.sh` header should say so.
- **L12. Race timing at 50 threads** — mock sender pool 32 threads, each holds a thread for the 100 ms delay; near 50 JMeter threads deliveries queue, webhook arrives later than D, skew toward cancel. `rejected = 0` does not detect queueing. Section 3 does not report the win split for the 50-thread runs.
- **L13. jmx generator not in the repo** — phase as-built says generated by a script; future edits are hand edits of XML.

## Optional improvements
- SQL: `payments_stuck_created` after quiescence; APPLIED SUCCESS event whose payment is not SUCCEEDED; non-CREATED payment with no transition row.
- `raw_body::jsonb`: safe for stored rows (insert happens only after Jackson parsed the body; missing fields give NULL, e.g. `MALFORMED_EVENT`). A signed body containing `\u0000` would abort the script (fail-closed, exit 3). Using `payment_id` instead of the cast in check 7 avoids a per-row parse.
- The final JSR223 sampler adds a ~0 ms sample per iteration to the JTL totals; read per-label rows only.
- `collect-metrics.sh`: `docker compose exec psql` every 2 s is itself load on the box; orphaned only on `kill -9` of `run.sh`.
- `hmac-sign.groovy`: `String.format` per byte is slow-ish in the generator; negligible here.
- Quiescence 20 s vs 15.5 s backoff + up to 6 x 5 s read timeouts: thin if the gateway is slow at run end.

## Categories with no finding
- (a) HMAC contract: header names, epoch millis, path without query, `Idempotency-Key` only on `POST /api/v1/payments` and removed elsewhere (no stale key on cancel), body bytes UTF-8 = bytes sent, `${orderId}` set before the body is read, cached script with script-level `toHex` is safe (new Script instance per eval). Timestamp is taken before timers run (<= 5 s stale on SyncTimer timeout; tolerance 5 min).
- (c) bash 3.2: arrays under `set -u`, `+=`, `trap EXIT`, `[ ] && ...` under `set -e`, quoting, heredoc-into-`exec python3`, stdin into `exec -T`, `COMPOSE_PROJECT` in both scripts, JMeter non-zero exit (aborts, trap kills metrics), same-second results dir collision (JMeter refuses, abort), Ctrl-C cleanup.
- (d) jmx structure: `Asserion.test_strings` is the real property name, `test_type` 1 = full regex match, `assume_success` makes expected 409 a pass, SyncTimer props, While condition (no commas), JSON extractor defaults, PreProcessor is a no-op for the JSR223 sampler and the mock pay URL.
- (e) override: actuator leaves 8080/8081, published on 127.0.0.1 only, port lists merge by append. Unauthenticated metrics also reachable from sibling containers; acceptable for a local load run.
- (g) `.gitignore`: `load-tests/results/` ignored; only secret in tracked files is the dev default `demo-secret-change-me` already in V2; no mock secret in `load-tests/`.

## Plan follow-ups (no plan files edited)
- Todo items are done as stated, with deviations recorded in as-built notes. Success criterion "first bottleneck named with metric evidence" is met for the fast-provider case in the weaker form "queue forms at the pool"; the slow-provider half needs M6.
- Open for the user: lock-mode toggle keep/remove (step 11).

## Unresolved questions
1. Were the burst 409s checked to be `IDEMPOTENCY_IN_PROGRESS` in the JTL, or inferred from the status code?
2. Is 405/495 in both columns of section 2 real?
3. Should RETRYABLE-after-quiescence be HARD (M5), given the plan listed it as a reported number?
4. Exercise 1: is "nothing breaks" the intended answer?

Status: DONE_WITH_CONCERNS
Summary: Signing, SQL syntax and shell portability hold up; the harness verdict is fail-open (H1), the burst scenario cannot catch an idempotency regression (M1), and two bottleneck conclusions in note 09 go beyond what one closed-workload run shows (M6, M7).
Concerns/Blockers: Numbers in note 09 not cross-checked against result files (off-limits); M8 needs a look by whoever has them.
