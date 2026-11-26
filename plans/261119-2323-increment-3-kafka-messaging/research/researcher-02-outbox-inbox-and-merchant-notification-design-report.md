# Increment 3 research: outbox, inbound inbox, idempotent consumer, merchant notifications

Date: 2026-11-19. Research only. No code or docs changed. Source tags (S1..S9) at end.

## 0. Verdicts (read first)

- **A+B (webhook path):** handler writes ledger row + outbox row in ONE tx, answers 200 after commit. Relay (single thread, `FOR UPDATE SKIP LOCKED`) publishes to Kafka. Generic `outbox` table (iii) beats `webhook_events`-as-outbox (ii) because D needs an outbox in the state-transition tx anyway. Direct dual write (i) is the failure demo only.
- **Simplicity honesty:** for A alone, the simplest correct design has no Kafka: ledger table as inbox + polling worker. Kafka is a plan/learning choice, not a correctness need on one instance. Say so in the note.
- **Retry after ack:** the provider no longer redelivers, so the consumer owns retry. Recommend re-drive from the table (`available_at` on outbox row). Blocking backoff only for ms-scale lock contention. Spring retry topics rejected.
- **C (idempotent consumer):** no separate processed-messages table. Ledger row lock + payment row lock + state transition + ledger status update in ONE tx. Duplicate = terminal ledger row = ack and skip.
- **D (merchant notifications):** one terminal notification per payment, written in `PaymentStateMachine.transition` (single choke point). Delivery via table-held schedule + `SKIP LOCKED` worker is the better fit (per-row isolation). Kafka for D is viable but has weaker isolation; owner decision (section 5).
- **E (measurement):** async moves DB borrows, it does not remove them. Expect webhook HTTP latency down, end-to-end time to terminal up, pool pressure roughly unchanged. Pre-register these predictions.

## 1. Webhook path shape (A+B)

**Current code facts**
- `WebhookIngestionService`: 4 separate txs (claim, apply, mark outcome). Answers 503 for in-flight or RETRYABLE; 200 for final outcomes.
- `WebhookPaymentApplier`: own tx with `findByIdForUpdate` + bounded retry; `markApplied` is a separate later tx (crash window, see Q4).
- `WebhookEventStore`: conditional updates keyed on `processing_status`; `insertNew` is the dedup arbiter.
- `PaymentStateMachine.transition`: only writer of status; appends `payment_transitions` row. Terminal states have no outgoing moves.

**Options**

| Option | Shape | Verdict |
|---|---|---|
| (i) Direct dual write | tx{insert ledger RECEIVED}; then `kafkaTemplate.send` | Reject for production. Loses the event if the JVM dies or the broker is down after commit: provider already got 200, nothing redelivers. Keep as failure demo (Q6). |
| (ii) `webhook_events` is the outbox | add `published_at`, `publish_attempts`, partial index | Runner-up. One row fewer, but two lifecycles (publish, processing) in one row; D still needs its own outbox. |
| (iii) Generic `outbox` table | ledger insert + outbox insert in one tx; relay publishes | **Recommended.** One mechanism for A and D, one relay class, notifications reuse it. |

**Recommended schema (iii), sketch**
```sql
create table outbox (
  id           uuid primary key,          -- also the consumer dedupe key and webhook-id for D
  topic        text not null,
  message_key  text not null,             -- merchantRef (payment id) for A; payment id for D
  payload      text not null,             -- A: webhook_events id only (claim check, one copy of raw_body)
  created_at   timestamptz not null,
  available_at timestamptz not null,      -- retry schedule (Q3)
  published_at timestamptz,
  attempts     int not null default 0,
  last_error   text
);
create index outbox_due on outbox (available_at, created_at) where published_at is null;
```
Use the DB clock (`now()`) in relay SQL, not the Java `Clock`, for `available_at` comparisons.

**Relay loop (one thread, one tx per batch)**
```sql
select id, topic, message_key, payload from outbox
where published_at is null and available_at <= now()
order by created_at, id
limit 100
for update skip locked;
-- publish in order; on first broker failure stop the batch (keeps per-key order), record last_error
update outbox set published_at = now() where id = any(:publishedIds);
commit;
```
- **Publish-then-mark gives at-least-once.** Crash after broker ack but before the `update` means the row is published again on the next poll. Mark-first would give at-most-once (loss on crash). Consumer dedupe (Q4) absorbs the duplicate.
- Producer `enable.idempotence=true` + `acks=all` removes duplicates from producer-internal retries (S5). It does NOT cover the relay-crash case above.
- **Polling interval:** 250–500 ms idle, loop immediately when a batch is full, batch 50–100 [design estimate, not sourced]. Idle cost at 250 ms is 4 borrows/s from a pool of 10.
- **Pool interaction:** the relay holds at most one connection, and only during a batch (broker round trip, ms on a laptop). Never sleep while holding a tx. Single relay thread also avoids two relays taking the same rows out of order.
- **Per-payment order:** key = payment id (`merchantRef`), same key for early and normal events. Order holds within one relay. Not guaranteed when two handler txs commit out of `created_at` order (timestamp is assigned before commit). Harmless here: terminal outcomes are first-wins in the state machine, so a reordered SUCCESS/FAILED pair changes only which event gets the REJECTED label. [analysis, not sourced]
- The outbox pattern itself does not promise publish order beyond "in the order the service produced them" (S1). The gap above is the honest version.

**Side effect on the increment-2 "known limits"**
- Handler inserts ledger RECEIVED and outbox row atomically, so a crash can no longer leave RECEIVED without a message. The "stuck RECEIVED after JVM crash" limit is closed by design (the consumer still needs the re-drive in Q3).
- With 200 after persist, the 503 machinery (`EVENT_IN_PROGRESS`, reclaim, in-flight duplicate 503) is no longer needed. Duplicate deliveries get 200. Finding #3 of the red-team table becomes moot. Plan acceptance text "early webhook ends SUCCEEDED after provider redelivery" changes to "after consumer retry". Plan doc update needed.

## 2. Is Kafka needed between "persisted" and "applied"?

Honest answer: **no, not for correctness on one instance.** The ledger table is already a durable inbox. Crash after commit loses nothing if a worker reads the table.

What Kafka adds here:
- **Partition ordering:** real, but one ordered relay + one ordered worker gives the same per-payment order (section 1).
- **Replay:** topic retention allows re-read from an offset. The table is already replayable by id, so this is no gain.
- **Consumer groups / horizontal scale:** none on one instance.
- **Back-pressure buffer:** the table buffers equally well. The only real difference: the broker keeps accepting while the consumer is down, which the table also does.
- **Cost:** a second durable store (broker) that must be kept consistent with the table. That consistency is exactly the outbox problem. Plus a broker container on a laptop, rebalance behavior, offset management.

**Simplest correct design for A alone:** no broker. Handler writes ledger RECEIVED; a `SKIP LOCKED` worker claims RECEIVED/retry-due rows and applies them. Rank by engineering simplicity: no-Kafka table queue < (iii) < (ii) < (i, broken).

Where Kafka earns its place: fan-out to several consumers (ledger, merchant notifications, future reconciliation) from one durable log. That is the plan's goal and the owner's learning goal. Write the note as: "Kafka here is for learning and fan-out; the table alone would be simpler."

## 3. Ack-then-process: what the consumer must guarantee

Cases that today rely on provider redelivery (503/500 from the current code):
- `PAYMENT_NOT_READY` (create tx2 not committed): seconds possible. Load test (note 09 §4.2) showed provider create p50 ~2 s under slow provider, so the create-to-PENDING gap can be seconds.
- `LOCK_TIMEOUT` / `CONCURRENT_UPDATE`: ms-scale contention.
- Unexpected error: today `releaseForRedelivery` + 500.
- Malformed / unknown payment / mismatch: final REJECTED, no retry. Keep.

**Guarantee to state:** every persisted event reaches a terminal ledger status (APPLIED or REJECTED) or has a scheduled next attempt. Nothing stops silently.

| Mechanism | Per-payment order | Blocks other payments | Retry state survives restart | Verdict |
|---|---|---|---|---|
| Blocking backoff in consumer (error handler with backoff) | strict | yes, whole partition | no (redelivered from offset: safe) | Use only for lock contention, total wait ≤ ~1 s |
| Spring non-blocking retry topics | broken across tiers | no | yes (topics) | Reject: extra topics per tier, ordering lost, YAGNI (S6) |
| **Relay re-drive via `outbox.available_at`** | not strict; safe because terminal is first-wins | no | yes (DB row) | **Recommend** for `PAYMENT_NOT_READY` and exhausted lock retries |

Mechanics of the recommended option: consumer on RETRYABLE sets `published_at = null`, `available_at = now() + backoff`, `attempts + 1`, then acks. Relay re-publishes when due. Backoff from ~1 s up to ~60 s. Ledger status RETRYABLE stays visible.

**Attempt cap: decision needed (reverses an earlier call).** The red-team table rejected a reclaim cap because the provider's schedule bounded retries. Once the consumer owns retry, nothing bounds it. Options:
- A. Cap N attempts (e.g. ~20 over ~10 min), then ledger REJECTED `RETRY_EXHAUSTED`. Simple, defensive, always terminates.
- B. No cap for not-ready. Terminates naturally if the sweeper fails abandoned CREATED rows (`failAbandonedCreated`), after which the event becomes REJECTED `PAYMENT_NEVER_ACCEPTED`. Lock retries have no natural end, so B still needs a cap for them.
- Recommendation: A. Needs owner decision. The created-timeout value was not read.

## 4. Idempotent consumer (C)

**What already holds:** conditional updates and first-terminal-wins make a duplicate *application* harmless. A second SUCCESS hits `ILLEGAL_TRANSITION` and is labelled REJECTED.

**What breaks:** the crash window. Payment transition commits (applier tx), then the process dies before `markApplied` (separate tx). Redelivery sees payment SUCCEEDED, rejects with ILLEGAL_TRANSITION. Result: a successful event labelled REJECTED. Same window exists today.

**Fix: one tx per event**
```text
tx:
  lock ledger row (select ... for update where id = ?)
  if status in (APPLIED, REJECTED): commit; ack; return          -- duplicate, no state-machine call
  lock payment (existing findByIdForUpdate, lock_timeout set)
  stateMachine.transition(...)                                    -- unchanged
  update ledger set status = APPLIED|REJECTED|RETRYABLE ...
commit; ack
```
- Lock order always ledger then payment. Cancel and sweeper lock only the payment, so no cycle.
- **Processed-messages table: not needed.** The ledger row is the processed record, committed atomically with the only side effect (payment row). A separate table is needed only for external side effects, i.e. the merchant HTTP call in D, which uses its own delivery state (section 5). Debezium's outbox write-up makes the same point: record processed in the same tx as the business work (S2).
- Concurrent duplicates (zombie consumer after rebalance): ledger row lock serializes; the second sees terminal and acks.

**Reproduce duplicates locally**
1. **Deterministic IT:** call the consumer's handler twice with the same record, then from two threads at once. Assert one `PROVIDER_WEBHOOK` transition row and duplicate counter incremented. Cheap, no broker needed.
2. **Kill before offset commit:** `enable.auto.commit=false`, ack after the tx. Sleep ~10 s after DB commit in the listener (test-only delay), `docker kill` the app during the sleep, restart. Message is redelivered; run the invariants.
3. **Forced rebalance:** `max.poll.interval.ms=5000`, `max.poll.records=1`; listener sleeps 8 s on first record; start a second consumer in the same group. First consumer's commit fails after rebalance and the record is redelivered (S4, S7 are secondary sources; verify against Kafka docs [UNVERIFIED official wording]).

## 5. Outbound merchant notifications (D)

**Industry behavior**

| | Stripe (S3) | Standard Webhooks (S8) | VNPAY / MoMo IPN |
|---|---|---|---|
| Success | any 2xx; redirects are failures | any 2xx; 3xx failure; 410 = disable endpoint; 429/502/504 = throttle | MoMo: merchant answers HTTP 204 (search summary, not fetched) [UNVERIFIED official] |
| Retry | up to 3 days, exponential backoff (live) | example: 10 attempts, 0 s to 24 h gaps, ~75 h total, with jitter | VNPAY/MoMo schedules not found [UNVERIFIED] |
| Signature | `Stripe-Signature: t=..,v1=..`; HMAC-SHA256 over `t.payload`; tolerance 5 min default; ignore non-v1 | headers `webhook-id`, `webhook-timestamp`, `webhook-signature`; HMAC-SHA256 over `msg_id.timestamp.payload`; base64; `v1,<sig>`; key = base64-decoded secret | VNPAY HMAC-SHA512 is third-party claim only [UNVERIFIED]; MoMo HMAC-SHA256 over key=value fields (search summary) |
| Ordering | none across events; use ids, not `created` | not specified | not found |
| Dedupe | event id; object id + type for duplicate events | `webhook-id` stable across retries = idempotency key | VNPAY: may send same IPN several times, handler must be idempotent (community best-practice page, not official) |
| Timeout | respond quickly, process async | 15–30 s recommended | not found |

Takeaways: merchants get **at-least-once with a stable id**, no ordering, and a retry window of days. Stable `webhook-id` across retries and a new timestamp per attempt is the common shape.

**Minimal design**
- **Write (exactly once per payment):** in `PaymentStateMachine.transition`, when `to` is terminal, insert a notification row in the same tx. The machine is the only status writer (verified in file) and terminal states are exclusive, so each payment gets exactly one terminal notification.
- **Row:** `merchant_notifications(id = webhook-id, payment_id, merchant_id, event_type, payload jsonb, status PENDING|DELIVERED|DEAD, attempts, available_at, last_status_code, last_error, created_at, delivered_at)`. Payload snapshotted at write time.
- **Payload:** `{id, type: "payment.succeeded"|"payment.failed"|..., created, data: {paymentId, merchantOrderId, amount, status}}`.
- **Delivery (table-held schedule, recommended):**
  1. short tx: claim due rows (`available_at = now()+lease`, `for update skip locked`), commit.
  2. HTTP POST outside any tx, connect/read timeout ~5 s.
  3. short tx: DELIVERED on 2xx; else `attempts+1`, `available_at = now()+backoff`; DEAD when attempts reach the cap or on 410.
  - Lease expiry covers a crash mid-call. Merchant may see a duplicate with the same `id`.
- **Signing:** v1 HMAC-SHA256 over `id.timestamp.body`, timestamp per attempt, `webhook-signature: v1,<base64>`. Key: merchant's existing HMAC secret (owner decision: reuse vs derived per-direction key). **Check** the merchant canonical string used for inbound requests: if the two formats overlap, a signed gateway output could be replayed as a merchant request. [UNVERIFIED: merchant signing code not read]
- **Schedule:** Standard Webhooks sample, shortened for a laptop (e.g. 0 s, 5 s, 30 s, 5 min, 30 min, ...), cap ~8 attempts [design estimate].
- **Dead letter:** `status = DEAD` + ERROR log + query. A Kafka DLT topic is the Kafka-native equivalent; add only if the owner wants to learn DLT. YAGNI says status row.

**Head-of-line blocking (slow merchant)**
- Key choice spreads load but does not isolate. Keyed by merchant: one slow merchant stalls all of its partition. Keyed by payment: a slow merchant still shares partitions with others.
- Isolation levers, simplest first:
  1. Short timeout (~5 s): bounds damage per call.
  2. No sleeping retries: backoff lives in `available_at`, no thread is held.
  3. Table worker with `SKIP LOCKED` and per-merchant in-flight cap (e.g. max 2): a slow merchant only occupies its own slots.
  4. Per-merchant circuit breaker: out of scope (plan).
- Kafka partitions are a fixed, coarse isolation tool (partition count = max parallelism; a stuck record blocks its partition). For D, the table worker isolates better.

**Recommendation for D:** table-held delivery schedule with `SKIP LOCKED` worker. Rank: (1) table worker, best isolation, fewest moving parts; (2) Kafka topic keyed by payment id, consumer uses the same claim/attempt steps with `outbox.available_at` for retry, works but weaker isolation; (3) Spring retry topics: reject (fixed delay tiers, head-of-line per tier). Choice of (2) is an owner decision (section 7).

**Merchant-side dedupe:** merchant stores seen `webhook-id`s; rejects replays using `webhook-timestamp` within tolerance. Gateway must document this.

## 6. Failure demos (deterministic, laptop)

| Pattern | Failure | Repro | Invariant / assertion | Expected before fix |
|---|---|---|---|---|
| B: dual write (i) | JVM dies or broker down after commit, before send | Testcontainers: stop Kafka, send webhook, check; or kill app between commit and send | `select count(*) from webhook_events where processing_status='RECEIVED'` after settle; nothing consumes it | > 0: event lost |
| B: outbox (iii) | broker down during a webhook burst | stop Kafka, send N webhooks (all 200), start Kafka | after settle: `select count(*) from outbox where published_at is null` = 0; `select count(*) from webhook_events e where not exists (select 1 from outbox o where o.payload = e.id::text)` = 0 | backlog drains; no ledger row without outbox row |
| C: duplicate | same event delivered twice, concurrently | two threads, same record, Testcontainers | `select payment_id from payment_transitions where to_status in ('SUCCEEDED','FAILED','CANCELLED','EXPIRED') group by 1 having count(*) > 1` = 0 (existing invariant) | must hold |
| C: crash window | payment committed, ledger not marked, then redelivery | fail the ledger update after payment commit (test seam throwing in real code path) | `select count(*) from webhook_events e where reject_reason = 'ILLEGAL_TRANSITION' and exists (select 1 from payment_transitions t where t.payment_id = e.payment_id and t.reason_code = 'PROVIDER_WEBHOOK')` = 0 | > 0 today: mislabelled event; = 0 after one-tx fix |
| C: kill before ack | app killed after DB commit, before offset commit | section 4 step 2 | same invariants as above, plus `count(*) from webhook_events where processing_status = 'RECEIVED'` = 0 after settle | redelivery handled, no double transition |
| D: slow merchant | merchant A stalls (30 s), merchant B healthy, same time | local HTTP stub with per-merchant delay | B's notification DELIVERED within ~1 s while A is pending (table worker); Kafka variant shows B delayed when sharing a partition | demonstrates HOL in the Kafka variant |
| D: 5xx then 2xx | merchant returns 500 for first 3 calls | stub with counter | `select count(*) from merchant_notifications where status = 'PENDING' and attempts >= cap` = 0 after settle; `select count(*) from payments p where p.status in ('SUCCEEDED','FAILED','CANCELLED','EXPIRED') and not exists (select 1 from merchant_notifications n where n.payment_id = p.id)` = 0 | DELIVERED, attempts = 4 |
| D: crash after 2xx | app killed after merchant 200, before DELIVERED mark | kill during stub response | stub sees the same `webhook-id` twice with different timestamps | duplicate expected, id identical |

Note: the invariant queries use no age filters (note 09 warns they check nothing after short runs). The "after settle" wait must exceed the longest retry window used in the test.

## 7. Measurement (E)

**Pre-register these predictions before running (note 09 lessons):**
- Webhook HTTP latency p50/p95: **down** (no transition in the request). Measure at gateway (`http.server.requests` for the webhook URI), not from the mock.
- End-to-end time to terminal status: **up** by about one relay interval plus consumer time (~0.25–0.5 s). Honest cost of async.
- Consumer lag and outbox backlog: sample `count(*) where published_at is null` (count, not age) every 2 s in `collect-metrics.sh`; Kafka consumer group lag via broker tooling.
- DB connection borrows per payment cycle: Hikari `hikaricp.connections.acquire` count per cycle. Webhook path today ~3–4 borrows (insert, load, transition, mark). Async: handler 1 + consumer 1 + relay amortized well under 1. Net drop of ~2 per webhook, but create (~5) and poll (~3) dominate note 09's cycle, so Hikari pending moves little.
- Race harness: webhook now lands later (queue delay), so webhook-wins vs cancel-wins ratio shifts and `cancelAfterMs` (60 ms + jitter) must be re-tuned, or results are not comparable to note 09 section 2. Pass criterion stays the invariants.
- Closed-workload caveat: async changes JMeter cycle time (webhook is driven by the mock, not JMeter, so it mostly affects cancel timing). Throughput comparisons need the same closed setup, plus 3 runs and median (note 09 says single runs were a weakness). An open-model run (constant-throughput timer) would show queueing honestly; [design suggestion, not verified in this harness].

**Result that would honestly show Kafka made nothing faster:** same throughput at 50 and 200 threads (within run-to-run noise over 3 runs), same Hikari pending, same create and poll p95, and end-to-end time worse. Conclusion to write: async webhook buys decoupling and lower webhook HTTP latency; the bottleneck from note 09 §4 (sync provider call holding request threads; create-dominated pool use) is untouched. If webhook HTTP p95 also does not drop, the webhook path was never the bottleneck, and that is a valid finding.

## 8. Limitations

- Official docs not retrieved: Apache Kafka client config (`max.poll.interval.ms`, idempotence and `max.in.flight` ordering), Spring for Apache Kafka 4.x retry/DLT reference (only API snippets and older versions), PostgreSQL `SELECT ... FOR UPDATE SKIP LOCKED` manual (only secondary write-ups, S9).
- MoMo and VNPAY retry, signature and status-code specs: only search summaries and third-party pages. Not usable as facts. Need merchant portal docs.
- Not read: merchant HMAC signing code, `PaymentRepository`, docker-compose, `created-timeout` and `lock-timeout` values, sweeper schedule. The D replay check and the attempt-cap math depend on them.
- Not checked: Kafka KRaft single-broker footprint on a laptop, Spring Boot 4.1 / Spring Kafka version compatibility, partition count sizing.
- Single run per claim; no measurement done here.

## 9. Owner decisions needed (present, then wait)

1. **Is D on Kafka or on the table worker?** Plan text says "retry and a dead-letter path", not Kafka. Options: (a) table worker, best isolation, fewer parts; (b) Kafka topic + table retry state, keeps Kafka learning on D, weaker isolation (partition HOL) accepted with timeouts. Recommendation: (a) unless Kafka-on-D is a learning goal.
2. **Attempt cap on consumer-owned retry** (section 3): cap N (recommended) vs no cap with sweeper termination. Reverses an earlier "no cap" call; needs explicit acceptance.
3. **Merchant signing key:** reuse the inbound HMAC secret vs derived per-direction key. Depends on the canonical-string check in section 5.
4. **Honesty framing for the note:** keep Kafka in A as learning and fan-out, and state the no-broker table queue as the simpler alternative (section 2).

## Unresolved questions

- Does the existing merchant HMAC canonical string overlap with the outbound format? (merchant signing code not read)
- Sweeper `created-timeout` value and whether it terminates not-ready retries (config not read).
- Kafka client version and whether `enable.idempotence` plus `max.in.flight` keeps order in this stack [UNVERIFIED].
- MoMo / VNPAY official retry schedule and signature algorithm [UNVERIFIED].
- Partition count (suggested 6, unsourced) and Kafka container memory on the laptop.

## Sources

- S1 Transactional outbox pattern (microservices.io): https://microservices.io/patterns/data/transactional-outbox.html
- S2 Debezium, reliable data exchange with the outbox pattern (outbox columns, processed-message dedupe in the same tx): https://debezium.io/blog/2019/02/19/reliable-microservices-data-exchange-with-the-outbox-pattern/
- S3 Stripe, receive events in your webhook endpoint (retries, 2xx, signature scheme, ordering, duplicates): https://docs.stripe.com/webhooks
- S4 Kafka rebalance and duplicate processing, secondary blog (commit failures after rebalance): https://oneuptime.com/blog/post/2026-07-22-kafka-rebalances-duplicate-processing/view
- S5 Confluent, Kafka delivery semantics and idempotent producer: https://docs.confluent.io/kafka/design/delivery-semantics.html
- S6 Spring for Apache Kafka, retry topic API docs (ordering trade-off): https://docs.spring.io/spring-kafka/docs/3.0.0-M5/api/org/springframework/kafka/retrytopic/RetryTopicConfigurationSupport.html (search snippets; current reference not retrieved)
- S7 ZIO Kafka, preventing duplicates (poll-interval sizing; secondary): https://zio.dev/zio-kafka/preventing-duplicates
- S8 Standard Webhooks specification (headers, signed content, HMAC, retry example, 2xx/3xx/410 handling, webhook-id idempotency): https://github.com/standard-webhooks/standard-webhooks/blob/main/spec/standard-webhooks.md
- S9 PostgreSQL SKIP LOCKED, secondary write-ups (2ndQuadrant): https://www.2ndquadrant.com/en/?p=9279 ; official manual not retrieved: https://www.postgresql.org/docs/current/sql-select.html
- Also consulted, weak: MoMo developer notification page https://developers.momo.vn/v3/docs/payment/api/result-handling/notification/ (search summary only); VNPAY community best practices https://vnpay.js.org/en/best-practices (not official).

Sources: [S1](https://microservices.io/patterns/data/transactional-outbox.html), [S2](https://debezium.io/blog/2019/02/19/reliable-microservices-data-exchange-with-the-outbox-pattern/), [S3](https://docs.stripe.com/webhooks), [S4](https://oneuptime.com/blog/post/2026-07-22-kafka-rebalances-duplicate-processing/view), [S5](https://docs.confluent.io/kafka/design/delivery-semantics.html), [S6](https://docs.spring.io/spring-kafka/docs/3.0.0-M5/api/org/springframework/kafka/retrytopic/RetryTopicConfigurationSupport.html), [S7](https://zio.dev/zio-kafka/preventing-duplicates), [S8](https://github.com/standard-webhooks/standard-webhooks/blob/main/spec/standard-webhooks.md), [S9](https://www.2ndquadrant.com/en/?p=9279)

Status: DONE_WITH_CONCERNS
Summary: Recommends generic outbox (iii) with a single relay, one-tx ledger+payment consumer (no processed table), relay re-drive for retries, and a table-held delivery worker for merchant notifications. States plainly that Kafka is not needed for correctness on one instance.
Concerns/Blockers: Attempt cap reverses an earlier decision and needs owner sign-off. Merchant signing code, sweeper config, and MoMo/VNPAY retry specs were not verified. Kafka official docs were not retrieved.
