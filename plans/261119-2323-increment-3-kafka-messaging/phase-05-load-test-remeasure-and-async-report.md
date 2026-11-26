---
phase: 5
title: "Load Test Remeasure and Async Report"
status: pending
priority: P2
effort: "2 sessions"
dependencies: [4]
---

# Phase 5: Load Test Remeasure and Async Report

## Context Links
- `docs/learning/09-load-test-and-bottlenecks.md` (baseline numbers, method lessons, §6 "what was not measured")
- `load-tests/run.sh`, `collect-metrics.sh`, `verify-invariants.sql`, `docker-compose.loadtest.yml`, the three `.jmx` scenarios
- Research: `researcher-02-...-report.md` §7. Red team: assumption findings 3, 4; security finding 1; failure-mode findings 6, 8

## Overview
Measure what asynchronous webhook handling and notifications changed, against the `increment-2-done` tag, with predictions written down before the run. The expected honest result: Kafka made the webhook HTTP call cheaper and nothing else faster.

## Key Insights
- Asynchrony moves database work, it does not remove it; the create and poll paths that dominated note 09 are untouched.
- Note 09's weak spot was one run per configuration. This phase runs each configuration 3 times and reports the median and the spread.
- A green run can be an empty run. That already bit the payment checks once; the same trap exists for notifications (a callback URL that matched no merchant makes every notification `SKIPPED` and every check green).
- The "before" build has no outbox tables and no broker: it must be measured with its own harness from its own tree, and only columns both harnesses produce can be compared.

## Requirements
- Functional
  - `verify-invariants.sql` new HARD rows: outbox rows unpublished after settle; ledger rows without an outbox row; applied event labelled `ILLEGAL_TRANSITION` (from phase 3); terminal payments without a notification row; with `NOTIFY=1` also notifications still `PENDING` and notifications `SKIPPED` (both must be 0). INFO rows: outbox row count, notifications by status, events `RETRY_EXHAUSTED`, notifications `DEAD`.
  - `collect-metrics.sh` samples: unpublished outbox count, consumer lag per group (`kafka-consumer-groups.sh --describe` inside the kafka container), plus the existing Hikari/Tomcat columns; webhook latency from the gateway's own `http.server.requests` for the webhook URI.
  - `run.sh`: `NOTIFY=1` sets the callback URL `where api_key = 'demo-merchant'` and fails unless exactly 1 row was updated; fails if `mock.merchant.bad_signature > 0` or `mock.merchant.stale > 0`, and fails if `mock.merchant.received` did not grow during the run. Reset between runs is `docker compose down -v` + `up` (database and broker volumes together), never a table truncate. Settle default raised to 30s with the reason in a comment (webhook reschedules seen in these scenarios are the 1-2s "not ready" steps; a row still `RETRYABLE` after settle fails the run rather than being waited for).
  - `run-matrix.sh`: runs a list of configurations N times each and collects result directories; no hand-run matrix.
- Non-functional: same laptop caveats as note 09, stated up front; no tuning in this phase.

## Predictions (written before any run; note 18 reports hit or miss for each)
1. Webhook HTTP p95 at the gateway drops (no transition in the request).
2. Time from "pay" to terminal status rises by roughly one relay interval plus consumer time (more while the relay is draining a backlog).
3. Baseline throughput at 50 and 200 threads is unchanged within run-to-run spread; Hikari pending at 200 threads is not lower.
4. Slow provider (2000ms, 400 threads): poll p50 still degraded; request threads are still the bottleneck.
5. With `NOTIFY=1`, terminal transitions cost two more inserts and an earlier flush; create and cancel p95 move by less than the run-to-run spread.

## Run matrix
Four configurations (cut from note 09's seven to keep the total honest): baseline 50 threads, baseline 200 threads, slow provider 2000ms at 400 threads, race 50x20. Three builds: before (tag), after with `NOTIFY=0`, after with `NOTIFY=1`. Three runs each: **36 measured runs**, plus about 6 control and outage runs. Session 1: harness changes, controls, "before" runs. Session 2: "after" runs, analysis, note.

## Related Code Files
- Modify: `load-tests/verify-invariants.sql`, `load-tests/collect-metrics.sh`, `load-tests/run.sh`, `load-tests/docker-compose.loadtest.yml` (kafka and mock merchant env)
- Create: `load-tests/run-matrix.sh`
- Modify: `load-tests/webhook-vs-cancel.jmx` (cancel timing parameters only if re-tuning is needed)
- Create: `docs/learning/18-async-remeasure-what-kafka-changed.md`
- Modify: `README.md` (increment 3 summary, layout, run, reset instruction), `docs/system-architecture.md`
- Modify: `./plan.md` acceptance checkboxes via `ck plan check`

## Implementation Steps
1. Add the invariant rows, metric columns, `NOTIFY` gates, reset and `run-matrix.sh`. Control runs, each must print `FAILED`: `-Jport=1`; broker stopped for the whole run (outbox invariant red); `NOTIFY=1` with the callback URL deliberately unset (notification gate red); fake merchant given a wrong key (`bad_signature` gate red).
2. **Before.** `git worktree add ../flux-pay-before increment-2-done`, copy `docker-compose.increment-2.yml` (saved in phase 1) and `.env` into it, bring that stack up and run the four configurations three times with **that tree's** `load-tests/` harness. This replaces note 09's single-run numbers as the comparison base. Compare only columns both harnesses produce (JMeter latencies and throughput, Hikari, Tomcat, the original invariants).
3. **After.** Same four configurations on the increment 3 build, three times each, `NOTIFY=0` then `NOTIFY=1`.
4. Race scenario: the webhook now lands later (relay + consumer). Measure the delay from `received_at` to `processed_at`, re-tune `cancelAfterMs`/`cancelJitterMs` so both sides still win a meaningful share, report the tuned values; pass criterion stays the invariants, and cancel must answer 200 or 409 only.
5. Broker-outage run under load: stop kafka for 20s during a 60s baseline run; record webhook error rate (expected 0), peak outbox backlog, drain time, lag, and how long the relay held its connection per poll (Hikari active while the broker is down).
6. Write note 18: method, predictions vs results table, what improved, what did not, the cost (two stores, a relay, later terminal status, an earlier flush), and a short "when would Kafka actually pay off here" section tied to the numbers. End with "Tự phá thử": relay interval 2s vs 200ms; listener concurrency 1 vs 3 with a slow merchant; `max.poll.records=500` with a 5s merchant delay.
7. Update README and architecture doc; `./mvnw verify`; mark phases with `ck plan check`; remove the worktree.

## Todo List
- [ ] Invariants + metrics + gates + matrix script; four control runs that go red
- [ ] "Before" x3 from the tag's own tree
- [ ] "After" x3, `NOTIFY=0` and `NOTIFY=1`
- [ ] Race re-tuned and reported
- [ ] Broker-outage run
- [ ] Note 18 with every prediction scored
- [ ] README + architecture doc

## Success Criteria
- [ ] All HARD invariants 0 on every reported run; each new invariant and gate shown red at least once in a control run.
- [ ] Every `NOTIFY=1` run delivered callbacks (`received` grew, `SKIPPED` = 0).
- [ ] Every table in note 18 names its command line, run count and spread.
- [ ] Each of the 5 predictions is marked held / did not hold with the numbers, including any that embarrass the design.
- [ ] No sentence in note 18 claims a speedup that is inside run-to-run spread.

## Risk Assessment
- Broker + JMeter + two apps + Postgres on one laptop: more CPU contention than note 09's runs. Rerunning the "before" on the same day keeps the comparison fair; never compare against note 09's old absolute numbers.
- The "before" stack depends on the compose snapshot taken in phase 1; if it was not taken, rebuild it by removing the kafka and merchant blocks from the current file and say so in the note.
- Consumer lag sampling by `docker exec` every 2s is itself load: sample every 5s if it shows up in CPU; say which was used.
- 42 runs with a full stack reset each: about 3 hours of machine time across two sessions; the matrix script makes it unattended.
- Old Kafka records after a database-only reset would become "ledger row missing" poison in the next run: the reset removes both volumes; the control for it is the DLT staying empty in normal runs (INFO row).

## Security Considerations
- Metrics stay on the management ports bound to `127.0.0.1` (existing override file). The broker's host listener stays on `127.0.0.1`.

## Next Steps
- Increment 4: reconciliation, provider retry + circuit breaker, per-merchant rate limit and failure budget. Inputs from this increment: `DEAD` notifications and `RETRY_EXHAUSTED` events need an operator or reconciliation path.
