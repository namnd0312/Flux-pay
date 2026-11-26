---
phase: 3
title: "At-Least-Once Consumer Idempotency and Dead Letter"
status: pending
priority: P1
effort: "1-2 sessions"
dependencies: [2]
---

# Phase 3: At-Least-Once Consumer Idempotency and Dead Letter

## Context Links
- Research: `researcher-02-...-report.md` §4, §6; `researcher-01-...-report.md` Q4
- Red team: failure-mode findings 3, 4; assumption findings 6, 8; security finding 8
- `gateway/.../webhook/WebhookEventProcessor.java` (from phase 2), `WebhookPaymentApplier.java`, `WebhookEventStore.java`, `payment/OptimisticRetry.java`, `payment/PaymentRepository.java`

## Overview
Kafka delivers at least once: the same record can arrive twice (relay republish, re-drive, crash before the offset commit, rebalance). Make the consumer safe by construction, and stop one bad record from being retried forever or silently dropped, without throwing away records that only hit a passing fault.

## Key Insights
- The offset commit and the database commit are another dual write. The fix is not "exactly once", it is "process twice, change state once".
- Today the payment commit and the ledger mark are two transactions. A crash between them, then a redelivery, labels a paid event `REJECTED ILLEGAL_TRANSITION`.
- Spring Kafka's default error handling retries a failing record 10 times with no delay, then logs and skips it (research; re-verify in step 5): a poison record is lost quietly.
- A dead-letter topic is for records that can never succeed. A database blip is not that: dead-lettering it after 600ms would turn a passing fault into a lost payment event.
- "The webhook path never loses a version check" (note 09) was measured on code that reads the payment outside the transaction. Inside one transaction an earlier non-locking read leaves a stale managed entity that the locking query hands back. The one-transaction design must not load the payment before it locks it.

## Requirements
- Functional
  - One transaction per event: lock ledger row -> if terminal, done -> resolve the payment **id only** (projection queries by provider ref, then by `merchantRef`) -> lock the payment (its first load in this persistence context) -> decide on the locked row (unknown, not ready, never accepted, ref or amount mismatch) -> transition -> mark ledger. Lock order is ledger then payment.
  - `PAYMENT_NOT_READY` is decided inside the transaction: mark `RETRYABLE` + reschedule, commit.
  - Lock timeout or version conflict leaves `tx.execute` as an exception (the transaction is dead); the processor catches it **around** the template and a second short transaction marks `RETRYABLE` + reschedules.
  - Dead-letter (`fluxpay.webhook-events-dlt`, original key, reason token header) only for `PoisonRecordException`: value not a UUID, ledger row missing, unknown provider code.
  - Any other exception reaching the listener (database unavailable, reschedule itself failed): the offset does not advance; the error handler retries that record with exponential backoff capped at 10s, without limit. The partition waits for the fault to pass; the phase 2 re-drive covers a record that still slips through.
- Non-functional: no processed-messages table; ack mode stays the Spring default (`BATCH`), explained in the note; with concurrency = partitions (phase 1) a waiting record stalls its own partition only.

## Architecture
- `WebhookEventProcessor.process` is one `TransactionTemplate` block plus the catch-and-reschedule around it. `WebhookPaymentApplier` becomes the in-transaction decision + transition on an already-locked payment; its own transaction entry point and `OptimisticRetry` wrapper are removed (the consumer is its only caller after phase 2). `OptimisticRetry` stays for cancel, create and compensate.
- `PaymentRepository`: id-only finders (`findIdByProviderCodeAndProviderRef`, existence by id).
- `WebhookEventStore.lockForProcessing(id)`: `select ... for update` returning status, provider code, raw body.
- `messaging/KafkaErrorHandlingConfig`: `DefaultErrorHandler` with `DeadLetterPublishingRecoverer` and an exponential backoff without an attempt limit; `PoisonRecordException` registered as not retryable (goes straight to the recoverer). Recoverer configured to add a reason token header and no stack trace or message headers. DLT topic bean with the same partition count as the source.

## Related Code Files
- Modify: `gateway/.../webhook/WebhookEventProcessor.java`, `WebhookPaymentApplier.java`, `WebhookEventStore.java`, `WebhookEventConsumer.java`, `payment/PaymentRepository.java`
- Modify: `gateway/.../messaging/KafkaTopicsConfig.java` (DLT topic)
- Create: `gateway/.../messaging/KafkaErrorHandlingConfig.java`, `PoisonRecordException.java`
- Create tests: `webhook/DuplicateDeliveryIT.java`, `webhook/HalfAppliedEventIT.java`, `webhook/CancelBetweenLookupAndLockIT.java`, `messaging/PoisonRecordIT.java` (listener IT, own context), `messaging/TransientFailureDoesNotDeadLetterIT.java` (listener IT)
- Modify tests: `webhook/WebhookFallbackDecisionTest.java` (decision logic moves onto the locked row; it also constructs `PaymentStateMachine` directly), `webhook/WebhookPessimisticLockIT.java`, `payment/WebhookVsCancelRaceIT.java` (its guard "webhook retry counter is 0" can no longer move once the retry loop is gone: replace with "no applied event labelled `ILLEGAL_TRANSITION`" and "both sides win at least once")
- Create: `docs/learning/16-at-least-once-idempotent-consumer-and-dead-letter.md`
- Modify: `load-tests/verify-invariants.sql` (mislabel check)

## Implementation Steps
1. **Failure A (mislabel).** `HalfAppliedEventIT` against the phase 2 processor: build the state a crash leaves (call the applier so the payment is `SUCCEEDED`, ledger still `RECEIVED`), then process the record. Assert the bug: ledger `REJECTED ILLEGAL_TRANSITION` for an event whose transition has reason `PROVIDER_WEBHOOK`. Capture output.
2. **Spike (Hibernate):** 10-line IT: load a payment without lock, commit a change from a second connection, run `findByIdForUpdate` in the same transaction. Record what comes back (stale instance, or an immediate version exception). Either way the design avoids the first load; the result goes into the note.
3. Rewrite the processor and applier as in Architecture. Rework `HalfAppliedEventIT`: the half state can no longer be produced by production code; instead hold the payment row lock from a second connection, process, and assert neither payment nor ledger moved, the outbox row is rescheduled, and after release the event ends `APPLIED`. `CancelBetweenLookupAndLockIT`: cancel commits after the id lookup and before the lock -> the event ends `REJECTED ILLEGAL_TRANSITION` in the same attempt, no reschedule.
4. **Failure B (duplicates).** `DuplicateDeliveryIT`: same record processed twice in sequence, then from two threads at once. Assert one terminal transition, ledger `APPLIED` once, no `ILLEGAL_TRANSITION`.
5. Verify against the Spring Kafka 4.1 reference (not memory): `DefaultErrorHandler` defaults, how to give not-retryable exceptions straight to the recoverer, unlimited backoff, DLT naming and partition rule, how to drop exception message/stack-trace headers, the ack-mode and concurrency property keys. Record page and any difference in the note.
6. **Failure C (poison).** `PoisonRecordIT` on default error handling: publish a non-UUID value followed by a valid record on the same partition; show the immediate retries in the log and that the bad record vanishes. Then add `KafkaErrorHandlingConfig`; assert the bad record is on the DLT with its key and a reason token (no payload, no stack trace) and the valid one is `APPLIED`.
7. `TransientFailureDoesNotDeadLetterIT`: make the processor's database work fail for a while by holding the ledger row lock past `lock_timeout` together with the outbox row (so the reschedule fails too); assert nothing reaches the DLT, the offset does not advance, and after release the event ends `APPLIED`.
8. Add to `verify-invariants.sql` (HARD): events `REJECTED ILLEGAL_TRANSITION` whose payment has a `PROVIDER_WEBHOOK` transition to the status that same event carries = 0.
9. Compose labs for the note: (a) under a JMeter `webhook-vs-cancel` run, `docker compose kill gateway`, start it again, run the invariants; (b) `docker compose kill kafka` mid-run and start it again: records the relay had marked published may be gone; watch the re-drive bring those events to a terminal state; (c) run a second gateway on the host (`SERVER_PORT=8082`, relay off) and watch `kafka-consumer-groups.sh --describe` reassign partitions; (d) read the DLT with `kafka-console-consumer.sh --property print.headers=true`.
10. Write note 16: at-least-once vs at-most-once vs "exactly once", offset commit timing and ack modes, idempotent consumer by ledger lock, why the payment is never read before it is locked, poison vs transient, blocking retry and what it stalls, DLT. `./mvnw verify`.

## Todo List
- [ ] Mislabel reproduced, then removed by the one-transaction processor
- [ ] Hibernate spike recorded; cancel-between-lookup-and-lock test
- [ ] Duplicate delivery ITs
- [ ] Poison record reproduced, then dead-lettered; transient failure proven not to dead-letter
- [ ] Defaults verified against current docs
- [ ] Race IT guards replaced; invariant added; compose labs run
- [ ] Note 16

## Success Criteria
- [ ] No test or load run produces an applied event labelled `ILLEGAL_TRANSITION`.
- [ ] Two concurrent deliveries of one record -> exactly one terminal transition.
- [ ] Poison record on the DLT with a reason token only; following record processed; lag returns to 0.
- [ ] A fault that passes (lock held, database briefly unavailable) dead-letters nothing and loses nothing.
- [ ] Killing the gateway, or the broker, mid-run leaves all HARD invariants at 0 after restart and settle.
- [ ] `./mvnw verify` green.

## Risk Assessment
- Longer lock hold (ledger + payment in one transaction): both single-row, same connection; `lock_timeout` stays; watch cancel p95 in phase 5.
- Deadlock: in this phase only the consumer takes two locks, ledger first; cancel and sweeper take the payment only. Phase 4 adds a third contended object (the notification row) and must keep the payment row first; see its lock-order section.
- An unlimited retry on a non-poison exception can stall a partition on a bug that looks transient: log each retry at WARN with the record coordinates so it is visible; consumer lag in phase 5 metrics would show it.
- Misclassifying a transient failure as poison (e.g. "ledger row missing" because the reader ran before the writer's commit is visible): cannot happen for outbox-published records, the relay publishes only committed rows; state this next to the check.
- DLT publish can fail with the broker down: the handler keeps retrying the record; acceptable.

## Security Considerations
- DLT records carry ids and a reason token only; no exception message, stack trace, payload or URL.

## Next Steps
- Phase 4 reuses outbox, relay, error handler and the reschedule mechanism for outbound notifications.
