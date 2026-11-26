---
phase: 2
title: "Async Webhook Ingestion with Transactional Outbox"
status: pending
priority: P1
effort: "2 sessions"
dependencies: [1]
---

# Phase 2: Async Webhook Ingestion with Transactional Outbox

## Context Links
- Research: `./research/researcher-02-outbox-inbox-and-merchant-notification-design-report.md` §1-§3
- Red team: `./reports/` failure-mode findings 3, 5, 6, 7, 8; assumption findings 2, 3, 5, 7; security findings 7, 8
- `gateway/.../webhook/WebhookIngestionService.java`, `WebhookEventStore.java`, `WebhookPaymentApplier.java`, `WebhookController.java`, `WebhookResult.java`
- Increment 2 limits: `../261112-2102-increment-2-concurrency-hardening/plan.md` ("Known limits carried forward")

## Overview
The webhook HTTP thread stops applying payments. It verifies, persists, answers 200; a Kafka consumer applies the transition. First with a direct `send` (the dual-write failure, reproduced), then with a transactional outbox and a relay.

## Key Insights
- "Commit to Postgres, then send to Kafka" is two systems and no shared transaction. The loss window is a process death, or a send that gives up, after the commit: the provider already got 200 and never sends again. A short broker pause alone loses nothing, because the producer buffers and retries inside `delivery.timeout.ms`.
- Publish-then-mark gives at-least-once: a relay crash after the broker ack republishes the row. Duplicates are the consumer's problem (phase 3).
- After 200 nothing outside redelivers, so "not ready yet" must be retried by us. Kafka has no delayed delivery; the delay lives in the outbox row.
- "Published" does not mean "processed". A record can be lost by the broker or never reach its outcome; the ledger row is the truth, and something must look at it again.
- Honest point for the note: the ledger table alone, read by a worker, would do this without a broker.

## Requirements
- Functional
  - `POST /webhooks/{provider}`: bad signature 401 (unchanged); otherwise one transaction inserts the ledger row `RECEIVED` and an outbox row, then 200 `{received:true, duplicate:false}`; known event id -> `duplicate_count++`, 200 `{received:true, duplicate:true}` (WARN log when bytes differ, via the existing `findExisting` body comparison).
  - Relay publishes due outbox rows in `created_at, id` order to `fluxpay.webhook-events`; key = `merchantRef` when it is a UUID, else the ledger id; value = ledger id; headers `fluxpay-outbox-id` and `fluxpay-outbox-attempt` (the row's `attempts` at publish time).
  - Consumer (group `fluxpay-webhook-applier`) loads the ledger row; `APPLIED`/`REJECTED` -> skip; otherwise re-parse `raw_body` with the provider, call `WebhookPaymentApplier.apply`, record the outcome.
  - Retry-later outcomes (`PAYMENT_NOT_READY`, `LOCK_TIMEOUT`, `CONCURRENT_UPDATE`, unexpected error): ledger `RETRYABLE` and reschedule: `update outbox set published_at = null, available_at = :now + backoff, attempts = attempts + 1 where id = :id and topic = :topic and payload = :payload and attempts = :seen and published_at is not null`. 0 rows = stale or foreign record: drop it, log at INFO. Backoff 1s doubling to 30s; at 12 attempts -> `REJECTED RETRY_EXHAUSTED` (user decision 2026-11-24).
  - Re-drive: on a slower tick the relay re-arms outbox rows published more than `fluxpay.outbox.redrive-after` (default 60s) ago whose ledger row is still `RECEIVED` or `RETRYABLE` (`published_at = null`, `attempts` unchanged).
- Non-functional
  - All schedule times come from the injected `Clock` and are passed into SQL as parameters; no `now()` in outbox SQL. Tests move time with `MutableClock`.
  - Relay holds at most one pooled connection, runs on its own single thread (not the shared scheduler), batch 20; a stopped broker must not hold the transaction longer than the configured producer timeouts.
  - Only the consumer's reschedule changes `attempts`; a failed publish records a reason token and leaves `attempts` alone.

## Architecture
```
provider -> WebhookController -> WebhookIngestionService.accept
              tx { webhook_events RECEIVED ; outbox row }  -> 200
OutboxRelay.publishDue(now)  (own single thread)
              tx { select due ... for update skip locked ; send + wait ack ; mark published_at }
OutboxRelay.redriveStale(now) (same thread, slower tick)
Kafka fluxpay.webhook-events (key = payment id)
WebhookEventConsumer -> WebhookEventProcessor -> WebhookPaymentApplier (unchanged) -> ledger outcome
                                             \-> OutboxStore.reschedule (retry later)
```
- `messaging`: `OutboxStore` (JdbcClient: `insert`, `lockDue`, `markPublished`, `recordPublishFailure`, `reschedule`, `rearmStale`), `OutboxRelay` (`publishDue(now)`, `redriveStale(now)`), `OutboxRelayRunner` (dedicated single-thread executor, `fluxpay.outbox.relay.enabled`, default true, false in the test profile; interval 200ms, loops at once when a batch was full).
- `webhook`: `WebhookEventConsumer` (`@KafkaListener`, thin; reads the two headers), `WebhookEventProcessor` (the logic; tests call it directly).
- `V13__create_outbox.sql`: `outbox(id uuid pk, topic varchar(100), message_key varchar(100), payload text, created_at, available_at, published_at null, attempts int default 0, last_error varchar(40))`, partial index `(available_at, created_at) where published_at is null`, **plus a backfill**: one outbox row for every existing `webhook_events` row in `RECEIVED`/`RETRYABLE` (those were promised a retry under the old 503 contract).
- `last_error` holds a fixed token (`BROKER_TIMEOUT`, `BROKER_SEND_FAILED`), never exception text, matching the repo's `reason_code` convention.
- Test base: `@BeforeEach` deletes all `outbox` rows (listeners and relay are off in the default test context, so nothing else owns them).

## Related Code Files
- Create: `gateway/src/main/resources/db/migration/V13__create_outbox.sql`
- Create: `gateway/.../messaging/OutboxStore.java`, `OutboxRelay.java`, `OutboxRelayRunner.java`
- Create: `gateway/.../webhook/WebhookEventConsumer.java`, `WebhookEventProcessor.java`
- Modify: `WebhookIngestionService.java` (accept only), `WebhookEventStore.java` (outcome writers accept `RECEIVED` or `RETRYABLE`; remove `reclaimRetryable`; keep `findExisting`), `WebhookController.java` + `WebhookResult.java` (no 503 branch, new body), `application.yml` (`fluxpay.outbox.*`, producer timeouts; fix the lock-timeout comment that mentions 503)
- Tests, by action (from grep of current assertions):
  - Rewrite: `webhook/WebhookIngestionIT` (11 body assertions), `webhook/OutOfOrderWebhookIT` (3), `webhook/WebhookPessimisticLockIT` (3; keeps its own property), `webhook/EarlyWebhookIT` (2 of 5 tests; its raw-SQL ledger insert must also insert an outbox row)
  - Delete: `EarlyWebhookIT` tests for `EVENT_IN_PROGRESS`, `EVENT_BODY_MISMATCH` and "200 or 503"; `webhook/WebhookIngestionServiceTest` (its only case, apply throwing on the HTTP thread, no longer exists) -> replaced by `webhook/WebhookEventProcessorTest`
  - Redesign: `payment/WebhookVsCancelRaceIT` (the barrier must release a processor call, not the HTTP request; 4 body assertions go)
  - Unchanged: `mock-provider` `WebhookSenderTest` (still retries any 5xx)
- Create tests: `messaging/OutboxRelayIT.java`, `webhook/LostAfterCommitIT.java`, `webhook/BrokerOutageIT.java`, `webhook/WebhookEventProcessorTest.java`
- Docs: create `docs/learning/15-dual-write-and-transactional-outbox.md`; modify `docs/system-architecture.md` (3 places describing the 503 path), `docs/learning/03-webhook-ipn.md` (2 mentions: add a forward pointer), `docs/learning/07-*.md` (forward pointer; rewrite exercise 1, which names `reclaimRetryable`), `README.md` webhook section
- Load-test comments only (logic changes in phase 5): `load-tests/run.sh` settle rationale, `load-tests/verify-invariants.sql` "provider gave up redelivering"
- Modify (local): `postman/` webhook requests

## Implementation Steps
1. **Failure first.** Implement accept + consumer with a direct `kafkaTemplate.send` after the ledger commit. `LostAfterCommitIT` (dedicated context: listeners on, short producer timeouts `delivery.timeout.ms`/`request.timeout.ms`/`max.block.ms`): pause the broker container for longer than `delivery.timeout.ms`, POST N signed webhooks (all 200), wait until the send futures have failed, unpause **in `finally`**, wait; assert the ledger rows stay `RECEIVED` and payments stay `PENDING`. Run it once before writing the note to confirm it really loses events; if the producer still delivers, lengthen the pause, do not change the claim. Keep the output and the naive snippet for note 15. Steps 2-4 replace the naive path in the same phase.
2. Add `V13` (with backfill), `OutboxStore`, `OutboxRelay`, `OutboxRelayRunner`. Verify the producer timeout keys and the rule `delivery.timeout.ms >= linger.ms + request.timeout.ms` against the Kafka docs; pick values so one blocked poll holds the transaction about 5s. Relay sends in order, waits for each ack, stops the batch at the first failure (keeps per-key order), marks the acked ids, commits.
3. Switch accept to the single transaction {ledger insert, outbox insert}. Remove the direct send.
4. `WebhookEventProcessor`: outcome mapping, generation-checked reschedule, cap. Remove `reclaimRetryable`, `EVENT_IN_PROGRESS`, the 503 response.
5. `LostAfterCommitIT` now passes after `publishDue`. `BrokerOutageIT`: same pause, then outbox rows of this test drain and its events are terminal. `OutboxRelayIT`: order per key; a second concurrent `publishDue` skips locked rows; crash simulation (publish, then roll back the mark) republishes the same row; a failed publish leaves `attempts` unchanged; `redriveStale` re-arms a published row whose ledger row is still `RECEIVED` and ignores terminal ones.
6. Processor tests: stale generation (two records for one row, second arrives after a reschedule) -> exactly one reschedule; forged `fluxpay-outbox-id` pointing at a row of another topic -> no row changes; cap on a `PENDING` payment (repeated `LOCK_TIMEOUT`, clock advanced) -> `RETRY_EXHAUSTED`. State in the note that `PAYMENT_NOT_READY` cannot reach the cap in a running system: the sweeper fails the abandoned `CREATED` row after 2 minutes and the next attempt ends `REJECTED PAYMENT_NEVER_ACCEPTED`.
7. Rewrite / delete / redesign the existing tests as listed. `EarlyWebhookIT` now proves the consumer-owned retry: event `RETRYABLE` with a future `available_at`, then `APPLIED` once create commits and the clock passes it.
8. Compose lab for the note: `docker compose stop kafka`, pay 5 orders, show 200s + `select count(*) from outbox where published_at is null`, `docker compose start kafka`, watch it drain and `kafka-consumer-groups.sh --describe` lag return to 0.
9. Write note 15: dual write and where exactly it loses, outbox, publish-then-mark, published vs processed and the re-drive, why not Kafka transactions, why the table alone would have been enough, the new webhook contract.
10. `./mvnw verify`.

## Todo List
- [ ] Naive path + `LostAfterCommitIT` showing real loss
- [ ] `V13` with backfill, outbox store, relay on its own thread
- [ ] Atomic accept; 503 machinery removed
- [ ] Processor with generation-checked reschedule, cap, re-drive
- [ ] Existing tests rewritten / deleted / redesigned as listed; new ITs green
- [ ] Docs and comments updated; compose lab run; note 15

## Success Criteria
- [ ] `LostAfterCommitIT`: events lost on the naive path, recovered on the outbox path.
- [ ] For rows created in a test: every ledger row has an outbox row.
- [ ] Early webhook ends `SUCCEEDED` without any provider redelivery, with no real-time wait longer than the Awaitility poll.
- [ ] Webhook answers 200 with the broker down; no 503 remains in `webhook` code.
- [ ] A duplicate or stale record never causes a second reschedule of the same generation.
- [ ] `./mvnw verify` green; added `verify` time under one minute.

## Risk Assessment
- Relay transaction spans broker round trips: bounded by batch 20 and the producer timeouts; one connection of ten. "Claim, then send with no transaction open" would hold nothing during the wait but needs a claim column and lease expiry; rejected as more state than a single relay needs. Describe it in the note as the alternative; measure the hold in phase 5.
- A consumer reschedule can wait behind the relay's row lock until that batch ends (same bound).
- Pausing the shared broker in two ITs: unpause in `finally`; these two classes use their own context with a unique group suffix (phase 1 rules).
- Reordering when two handler transactions commit out of `created_at` order: harmless, terminal states are first-wins; state it in the note instead of claiming strict order.
- Contract change for providers (no `applied`/`rejectReason` in the body, no 503): follows from ack-then-process; only the mock consumes it.
- Outbox grows forever: no purge in this increment; `INFO` row count in the invariants, listed as a limit.
- Re-drive republishes an event that is merely slow (consumer lag above 60s): a duplicate, absorbed by the idempotent consumer of phase 3.

## Security Considerations
- Signature check stays before any write; the Kafka record carries ids only, never the signed body or secrets.
- A record is untrusted input even from our own broker (no auth on it): the consumer acts only on the ledger row it loads, and the reschedule checks topic, payload and generation of the outbox row named by the header.

## Next Steps
- Phase 3 hardens the consumer against duplicates, crashes and poison records.
