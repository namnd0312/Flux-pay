---
phase: 4
title: "Outbound Merchant Notifications"
status: pending
priority: P2
effort: "2-3 sessions"
dependencies: [3]
---

# Phase 4: Outbound Merchant Notifications

## Context Links
- Research: `researcher-02-...-report.md` §5-§6 (Stripe and Standard Webhooks behaviour verified; VNPAY/MoMo schedules unverified, do not cite as fact)
- Red team: failure-mode findings 1, 5; assumption findings 1, 5, 6, 7; security findings 1-6, 8
- `gateway/.../payment/PaymentStateMachine.java` (single writer of status), `payment/OptimisticRetry.java`, `merchant/Merchant.java`, `merchant/SecretCipher.java`, `common/HmacSigner.java`, `common/CanonicalRequest.java`, `common/ProblemDetailsHandler.java`
- `mock-provider/.../webhook/WebhookSender.java` (the mirror image of what the gateway now becomes)

## Overview
When a payment reaches a terminal state the gateway tells the merchant with a signed HTTP callback, at least once, with a stable id, through a second Kafka consumer group (user decision 2026-11-24). The gateway is now on the sending side of everything it learned as a receiver in increments 1-2.

## Key Insights
- The event must be written in the same transaction as the transition, or a crash produces a finished payment nobody is told about: the outbox again, this time for business events.
- One topic feeding a second consumer group is the fan-out that justifies the broker.
- Adding a row with a unique key to the transition transaction adds a lock. Cancel and the sweeper do not take the payment row lock; if their notification insert runs before their payment `UPDATE`, they contend with the webhook consumer in the opposite order: deadlock, or a duplicate-key error that `OptimisticRetry` does not retry, so cancel answers 500 instead of 409. Every terminal writer must touch the payment row first.
- A slow merchant blocks the listener thread it is on. With one thread per partition that is its partition; nothing isolates merchants from each other inside a partition. Timeouts and "no sleeping retries in the listener" bound the damage; they do not remove it.
- Merchants get at-least-once and no ordering. `webhook-id` stays the same across attempts so they can deduplicate; the timestamp changes per attempt and lets them reject replays.

## Requirements
- Functional
  - `PaymentStateMachine.transition` to `SUCCEEDED / FAILED / EXPIRED / CANCELLED`: **flush the payment update first** (`PaymentRepository.flush()`: version check and row lock happen here, and a lost race surfaces as the optimistic failure the callers already retry), then insert one `merchant_notifications` row (`PENDING`, payload snapshot; `on conflict (payment_id) do nothing` as a backstop) and one outbox row (topic `fluxpay.payment-notifications`, key = payment id, value = notification id).
  - Consumer group `fluxpay-merchant-notifier`: load the row; not `PENDING` -> skip; merchant inactive -> `SKIPPED MERCHANT_INACTIVE`; no `callback_url` -> `SKIPPED NO_CALLBACK_URL`; secret cannot be decrypted -> `DEAD SECRET_UNAVAILABLE`; else POST and record the result.
  - Request: body `{id, type: "payment.succeeded|failed|expired|cancelled", created, data:{paymentId, orderId, amount, status}}`; headers `webhook-id`, `webhook-timestamp` (epoch seconds, per attempt), `webhook-signature: v1,<base64 HMAC-SHA256(callbackKey, id + "." + timestamp + "." + body)>` over the exact bytes sent.
  - `callbackKey = HMAC-SHA256(merchant secret, "fluxpay-callback-v1")` (user decision 2026-11-24): whoever verifies callbacks cannot sign API requests.
  - 2xx -> `DELIVERED`. Anything else (non-2xx, 3xx, timeout, connection error) -> `attempts + 1`, generation-checked outbox reschedule (phase 2 mechanism) with backoff 1s, 5s, 15s, 60s, 120s; after 6 attempts -> `DEAD` (ERROR log with ids and reason token). Result stored as `last_status_code` + reason token (`TIMEOUT`, `CONNECT_FAILED`, `HTTP_3XX`, `HTTP_4XX`, `HTTP_5XX`).
  - Poison records -> `fluxpay.payment-notifications-dlt`; the relay re-drive also covers `PENDING` notifications published more than `redrive-after` ago.
- Non-functional: HTTP call outside any database transaction, through a dedicated `RestClient` with explicit connect 2s / read 5s and **redirects off**; the response body is never read; `max.poll.records` for this listener small enough that records x 7s stays far below `max.poll.interval.ms`; schedule times from the injected `Clock`.

## Architecture
```
PaymentStateMachine.transition (terminal)
   tx { payment status -> flush ; payment_transitions ; merchant_notifications PENDING ; outbox row }
OutboxRelay -> Kafka fluxpay.payment-notifications (key = payment id)
MerchantNotificationConsumer -> MerchantNotificationDeliverer
   read row + merchant (short tx) -> sign -> POST (no tx) -> record result (short tx) [-> OutboxStore.reschedule]
```
- New package `com.fluxpay.gateway.notification`: `MerchantNotificationRecorder` (called by the state machine), `MerchantNotificationStore` (JdbcClient), `MerchantNotificationSigner` (key derivation + signature), `MerchantNotificationDeliverer`, `MerchantNotificationConsumer`.
- `V14__add_merchant_notifications.sql`: `merchants.callback_url text null` (check: starts with `http://` or `https://`); `merchant_notifications(id uuid pk, payment_id uuid not null unique, merchant_id uuid not null, event_type varchar(40), payload text, status varchar(20), attempts int default 0, last_status_code int, last_error varchar(40), created_at, delivered_at)`.
- Fake merchant in `mock-provider`, package `merchantsim`: `POST /merchant-sim/notifications` verifies the signature with the derived key, rejects a `webhook-timestamp` outside the tolerance, remembers seen ids; counters `mock.merchant.received / duplicate / bad_signature / stale`; knobs `MOCK_MERCHANT_DELAY_MS`, `MOCK_MERCHANT_FAIL_FIRST_N`, `MOCK_MERCHANT_CALLBACK_KEY`, `MOCK_MERCHANT_TOLERANCE_S`.
- The callback URL is set by SQL only (no API): `update merchants set callback_url = ... where api_key = 'demo-merchant'` (the seeded api key; `DEMO` is the name). The derived key for the fake merchant is computed once with `openssl` (command in the note).

### Lock order (every terminal writer)
payment row (flushed `UPDATE` or the consumer's `FOR UPDATE`) -> `merchant_notifications` unique entry -> outbox insert (no contention). The webhook consumer adds the ledger row in front. No path takes the notification entry before the payment row.

## Related Code Files
- Create: `gateway/src/main/resources/db/migration/V14__add_merchant_notifications.sql`
- Create: `gateway/.../notification/` (5 classes above)
- Modify: `gateway/.../payment/PaymentStateMachine.java` (+ constructor), `merchant/Merchant.java` (`callbackUrl`), `messaging/KafkaTopicsConfig.java` (topic + DLT), `messaging/OutboxRelay.java` (re-drive for notifications), `application.yml` (`fluxpay.notifications.*`)
- Modify tests: `webhook/WebhookFallbackDecisionTest.java` (the only place that calls `new PaymentStateMachine(...)`; one of its transitions is terminal); `payment/OptimisticLockConflictIT.java` (two direct terminal transitions on one payment); `payment/PaymentStateMachineTest.java` needs no change (static table check only)
- Gate tests (must stay green unchanged in intent): `payment/WebhookVsCancelRaceIT`, `payment/OptimisticLockConflictIT`, `load-tests/webhook-vs-cancel.jmx` (cancel answers 200 or 409, never 5xx)
- Create tests: `notification/NotificationLockOrderIT.java`, `notification/MerchantNotificationIT.java`, `notification/MerchantNotificationSecurityIT.java`, `notification/SlowMerchantHeadOfLineIT.java` (listener IT), `notification/MerchantNotificationSignerTest.java`
- Create: `mock-provider/.../merchantsim/MerchantNotificationReceiver.java`, `MerchantSimProperties.java`; modify `mock-provider/src/main/resources/application.yml`
- Create: `docs/learning/17-merchant-notifications-and-head-of-line-blocking.md`
- Modify: `README.md` (callback contract and merchant obligations), `docs/system-architecture.md`, `postman/` (local)

## Implementation Steps
1. **Failure first (lock order).** Add `V14`, recorder and store with the naive order (insert in `transition`, no flush). `NotificationLockOrderIT`: cancel and a webhook process call race on one payment, many rounds. Capture what the naive order produces (500 from a duplicate key, or a deadlock abort after about 1s). This is reasoned from the code, not yet observed: if it does not reproduce, record that and keep the flush anyway only if the trace shows the opposite lock order is reachable. Then add the flush; the IT and both gate tests pass with cancel answering 200 or 409 only.
2. **Failure (lost notification).** Show in `MerchantNotificationIT` that a notification sent directly after the transition commits is lost when the send fails (same mechanism as phase 2), then rely on the outbox row written in the transition. Invariant: terminal payments without a notification row = 0.
3. Check every caller of `transition` runs inside a transaction (orchestrator create/compensate/cancel/sweep, webhook processor) and that the flush exception is the translated `OptimisticLockingFailureException` on each path.
4. Signer + unit test. Test vector computed outside the code under test (`openssl dgst -sha256 -hmac ... -binary | base64`, command recorded in the test). Negative cases: body, timestamp, id, key each altered -> signature differs. Javadoc: the signed string `id.timestamp.body` cannot be mistaken for the inbound canonical string (`METHOD\nPATH\n...`).
5. Deliverer + consumer. Tests use a real local HTTP server (`com.sun.net.httpserver.HttpServer`) that recomputes the HMAC over the raw request bytes with plain `javax.crypto` and fails the test on mismatch. Each test creates its own merchant row (own secret, own callback URL) and calls the deliverer directly. Cases: 2xx -> `DELIVERED`; 500 three times then 200 -> `DELIVERED`, `attempts = 3`, same `webhook-id` on all four requests, different timestamps (clock advanced between attempts); always 500 -> `DEAD` after 6; no callback URL / inactive merchant -> `SKIPPED`; record for a `DELIVERED` row -> no HTTP call; stale-generation record -> no second reschedule.
6. `MerchantNotificationSecurityIT`: stub answers 302 to a second stub, which must record zero hits and the attempt counts as `HTTP_3XX`; a merchant returning a 500 with a very long body does not break the result update; undecryptable secret -> `DEAD SECRET_UNAVAILABLE`. Verify on Boot 4.1 how redirects are disabled for a `RestClient` (the JDK client follows them by default) and that the read timeout bounds the wait for headers.
7. **Failure (head-of-line).** `SlowMerchantHeadOfLineIT` (listeners on, concurrency = partitions): merchant A's endpoint stalls past the read timeout. Three measurements: B on the **same** partition as A (payment ids chosen with Kafka's default partitioner so both hash to one partition) waits behind A; B on a **different** partition is not delayed (control); a backlog of 20 stalled records for A, then one for B on the same partition (shows the bound is per record, not per merchant). Record the numbers; do not claim isolation.
8. Fake merchant endpoint in `mock-provider`; compose env; lab for the note: set the callback URL, pay, watch `mock.merchant.received`; `MOCK_MERCHANT_FAIL_FIRST_N=3` and watch attempts; kill the gateway right after a delivery and observe the duplicate id at the receiver; replay a captured request by hand after the tolerance and watch `mock.merchant.stale`.
9. Write note 17: outbox on business events, lock order when a transaction gains a row, fan-out and consumer groups, derived signing key, at-least-once and replay protection for merchants, head-of-line blocking with the measured numbers, why a table worker isolates better, what is not done about SSRF (resolve-then-check for private/loopback/link-local addresses, DNS rebinding, egress proxy; required before any merchant-supplied URL), what real gateways do differently (retry for days, replay tooling, key ids for rotation). `./mvnw verify`.

## Todo List
- [ ] Lock-order failure reproduced or disproved, flush in place, gate tests green
- [ ] Lost-notification failure reproduced, then closed by the transition-time outbox write
- [ ] Signer with derived key, external vector, negative tests
- [ ] Deliverer + consumer + ITs with independent signature check
- [ ] Redirect, long-body and secret-failure cases
- [ ] Head-of-line demo: same partition, control, backlog
- [ ] Fake merchant endpoint with tolerance + compose lab
- [ ] Note 17, README callback contract

## Success Criteria
- [ ] Cancel racing a webhook still answers 200 or 409 only (`WebhookVsCancelRaceIT`, JMeter scenario); no deadlock aborts in the Postgres log.
- [ ] For payments created in a test: every terminal payment has exactly one notification row.
- [ ] Fail-then-recover merchant ends `DELIVERED`; the receiver saw one `webhook-id`; the independent HMAC check passed on every request.
- [ ] A redirect is never followed; an inactive merchant receives nothing.
- [ ] A duplicate or stale record never causes a second POST for a `DELIVERED` row.
- [ ] Slow-merchant test reports the delay for same-partition, different-partition and backlog cases.
- [ ] `./mvnw verify` green.

## Risk Assessment
- The flush moves the version check from commit time to the middle of the transaction on every terminal path: behaviour is the same exception one step earlier; covered by the gate tests. `on conflict do nothing` alone would not fix the deadlock (it still waits on the in-flight row).
- Every terminal transition now writes two more rows (create-failure, sweeper, cancel paths too): measured in phase 5.
- Crash after the merchant answered 2xx but before `DELIVERED` is stored -> duplicate callback. Intended (at-least-once); documented with the dedupe rule.
- A hostile or slow merchant with many terminal payments (create then cancel is cheap) can occupy its partition's thread for a long time. Bounded only by timeouts; a per-merchant failure budget or circuit breaker belongs to increment 4 and is listed as a known limit.
- Rebalance while a POST is in flight: bounded by small `max.poll.records`; verify the property key against current docs.
- `payment` now depends on `notification`: one direct call, no event bus (must be the same transaction). Note the direction in `docs/system-architecture.md`.
- A notification is sent for every terminal state, including `FAILED` at create (the merchant also got a 502) and `CANCELLED` (the merchant asked for it): one simple rule; revisit if it reads as noise.

## Security Considerations
- SSRF: `callback_url` is operator-set by SQL only and scheme-checked; redirects are off and tested; no response body is read or stored. Host and address checks are not implemented and the note says so.
- Signing: derived per-direction key; the fake merchant holds only the derived value. No key id, so rotation needs both sides to switch together: stated as a limit.
- Stored failure details are fixed tokens and a status code; no URL, body or exception text in the database, logs at ERROR, or DLT headers.
- Receiver obligations in the README: verify over raw bytes, constant-time compare, reject timestamps outside the tolerance, deduplicate by `webhook-id`; `http://` callbacks are for local development only.

## Next Steps
- Phase 5 measures the cost of both asynchronous paths.
