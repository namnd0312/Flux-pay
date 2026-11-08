# Brainstorm Report: Flux-pay Mini Payment Gateway (learning-first)

- Date: 2026-11-06
- Mode: brainstorm (no --html, no --wiki)
- Status: design approved by user
- Repo: `/Users/admin/Desktop/DEV/AI/Flux-pay` (greenfield, empty, not yet git)

## 1. Problem statement

User wants to **learn from zero** how a payment gateway (VNPAY/MoMo style) stays correct under concurrency, duplicate requests, duplicate/concurrent provider callbacks, and async processing. Deliverable is a backend-only REST system plus a set of learning notes. Code is evidence of understanding, not the end product.

Learning goals (verbatim intent): gateway architecture + payment lifecycle, safe concurrent requests, idempotency, DB transactions + locking, duplicate/concurrent provider callbacks, Kafka async, throughput/resilience, load testing + bottleneck analysis.

Out of scope (all increments): frontend, mobile, real money, real bank/provider integration.

## 2. Problem-first check (concise)

- **Solution-jumping signal:** request arrived as 9 modules + 4 patterns + Kafka.
- **Underlying problem:** no hands-on mental model of why gateways fail (double charge, double state transition, lost callback) and what fixes each failure.
- **Assumptions challenged:**
  - "9 modules needed day one" → risk: structure without understanding. Validation: start with 6 packages, add `messaging`/`reconciliation` when their increment starts.
  - "Reconciliation belongs in v1" → separate subsystem, moved to increment 4.
  - "Kafka needed" → it is a learning goal, kept, but only in increment 3 after sync correctness exists.
- **Success looks like:** user can explain each failure mode, reproduce it with Postman/JMeter, and point to the code + test that prevents it.
- **Evidence status:** strong for the learning goals (stated explicitly); weak for module list (copied from typical gateway layouts).

## 3. Scout findings

- Siblings in `DEV/AI` all use Spring Boot + Maven + PostgreSQL + Flyway; newer ones add Testcontainers, JaCoCo. Java 21, Maven 3.9.1, Docker 29, Node 24 installed. No Kafka/Redis anywhere yet.
- Sibling `english-study` has `jmeter.log` → JMeter already familiar.
- No existing docs/plans in Flux-pay.

## 4. Decisions (captured via AskUserQuestion)

| Topic | Decision | Alternatives rejected |
|---|---|---|
| Stack/layout | Spring Boot 4.1 (sibling convention), Java 21, Maven, PostgreSQL, Flyway. Modular monolith: one gateway app, one package per module | Maven multi-module (build friction), microservices (infra noise hides concurrency lessons) |
| Mock provider | Separate small Spring Boot app `mock-provider/`, calls gateway webhook over real HTTP with HMAC, configurable delay/duplicate/out-of-order | In-process adapter (never exercises real inbound webhook) |
| Lifecycle v1 | CREATED, PENDING, SUCCEEDED, FAILED, EXPIRED, CANCELLED. No refunds | Refund states (second state machine, later increment) |
| Load tool | JMeter (.jmx in repo) | Gatling, both |
| Merchant auth | API key (`X-Merchant-Id`) + HMAC-SHA256 over timestamp+body with per-merchant secret; 5-min timestamp window | API key only, JWT login |
| Kafka first topic | Inbound webhook ingestion (verify → persist raw → publish → consumer applies transition under lock) | Outbound notifications first, both at once |
| Locking | Optimistic (`@Version` + retry) first, observe conflicts under JMeter, then `SELECT FOR UPDATE` on webhook path | Pessimistic everywhere |
| Increments | 4 increments, first plan = increment 1 only | Merge 1+2 |
| Learning mode | Claude writes all code with step-by-step explanation; user reads, runs, breaks it | User writes / hybrid |
| Zero baseline | Payment domain, concurrency+locking, Kafka. Spring Boot NOT zero | — |
| Notes language | Vietnamese prose, English terms, in `docs/learning/` | English |

## 5. Final design (increment 1)

### Repo layout
```
Flux-pay/
  pom.xml                 # aggregator
  gateway/                # modular monolith
    com.fluxpay.{common,merchant,payment,provider,webhook,idempotency}
  mock-provider/          # fake VNPAY, in-memory sessions, sends signed webhooks
  load-tests/             # JMeter .jmx + runner script (increment 2)
  docker-compose.yml      # postgres, gateway, mock-provider (kafka in inc 3)
  docs/learning/          # one note per lesson, Vietnamese
  postman/                # collection + env
```
Orchestrator = one class inside `payment`. `messaging` added in inc 3, `reconciliation` in inc 4.

### Flow (redirect style, VNPAY-like)
1. `POST /api/v1/payments` with `X-Merchant-Id`, `X-Timestamp`, `X-Signature`, `Idempotency-Key`.
2. Gateway: verify HMAC, reject stale timestamp, check idempotency key, insert payment CREATED, call `PaymentProvider.createPayment`, store providerRef + payUrl, transition → PENDING, commit, return payUrl.
3. "Customer" (Postman/JMeter) calls mock-provider pay endpoint with outcome success|fail.
4. Mock-provider POSTs signed webhook → `POST /webhooks/{providerCode}`.
5. Gateway: verify provider signature, insert `webhook_events` (unique on provider_code+event_id), apply transition via state machine, 200. Duplicate → constraint hit → 200, no second transition.
6. Merchant polls `GET /api/v1/payments/{id}`. Push notifications arrive with Kafka in inc 3.

### State machine
enum + explicit transition table + single `transition()` entry point. Terminal states reject further transitions (409). Every transition appended to `payment_transitions` audit table.

### Provider abstraction
- `PaymentProvider` interface: `createPayment`, `verifyWebhookSignature`, `parseWebhook` → normalized event, `queryStatus` (used in inc 4), `code()`.
- `MockProviderAdapter` (Adapter) over HTTP client.
- `ProviderRegistry` (Factory) built from Spring beans, resolved by code (Strategy). New provider = one class + config.

### Idempotency
Key scoped per merchant; stores request-body hash, response status, response body; TTL 24h. Same key + different body → 422. Same key in flight → 409. Same key + same body → replay stored response. Second guard: unique (merchant_id, order_id).

### Data model (Flyway)
`merchants`, `payments` (version col, amount as long VND, expires_at), `payment_transitions`, `idempotency_keys`, `webhook_events`. Merchant secret encrypted at rest with local key (HMAC needs raw secret).

### Errors
RFC 7807 problem details + stable `code` field.

### Tests (inc 1 must prove)
Idempotent replay; duplicate webhook rejected; invalid signature rejected; illegal transition rejected; N threads same create request → exactly 1 payment row. Testcontainers PostgreSQL.

## 6. Learning-path structure

Every step follows: **reproduce the failure → concept → fix → self-verify**. Each step ships: code, Postman requests, one note in `docs/learning/NN-<topic>.md` (VN prose, EN terms), and a test that fails before / passes after.

### Increment 1 steps
| # | Step | Lesson note | Failure demo |
|---|---|---|---|
| 0 | Skeleton: Spring Boot, Postgres, Flyway, Compose, health | gateway anatomy, actors (merchant, gateway, provider, customer) | — |
| 1 | Payment create/get + state machine (no auth, no provider) | state machine, why `if status==` sprawl fails | late "success" webhook overwriting EXPIRED |
| 2 | `PaymentProvider` interface + mock-provider app + adapter | Strategy/Adapter/Factory, redirect flow, payUrl, providerRef | — |
| 3 | Sync webhook + signature verify + de-dup constraint | IPN, why provider retries, at-least-once delivery | send same webhook twice → double transition |
| 4 | Merchant auth HMAC | how VNPAY/MoMo sign, replay window | forged request accepted before fix |
| 5 | Idempotency | double-charge, Idempotency-Key semantics | 50 identical requests → 50 payments |

### Increment 2: concurrency hardening
Optimistic lock + retry → JMeter shows conflict rate → pessimistic on webhook path. Tests: concurrent duplicate webhooks; webhook racing the create request; JMeter baseline + bottleneck report (sync provider call is the expected first bottleneck).

### Increment 3: Kafka
Topics/partitions/consumer groups from zero. Transactional outbox. Inbound webhook ingestion topic + consumer idempotency. Then outbound merchant notifications with retry. Compose adds Kafka.

### Increment 4: resilience + reconciliation
Retry + circuit breaker on provider calls, per-merchant rate limit, expiry sweeper, reconciliation job using `queryStatus`.

## 7. Risks

- Sync provider HTTP call inside create request → first bottleneck. Intentional, exposed in inc 2.
- Merchant secret storage for HMAC → encrypted with local key; not production grade, say so in notes.
- Webhook may arrive while payment still CREATED if provider is fast → inc 1 commits PENDING before returning payUrl; inc 2 tests the race explicitly.
- Learning mode "Claude writes all" risks passive reading → every step ends with a "break it yourself" exercise in the note.
- Scope creep into refunds/multi-currency → explicitly deferred.

## 8. Success metrics

- Each inc-1 step runnable + verifiable via Postman within one session.
- All 5 inc-1 tests green on Testcontainers.
- User can explain, without notes, why each of: idempotency, unique constraint on webhook events, state machine, HMAC timestamp window exists.
- Inc 2: JMeter report shows conflict/duplicate count = 0 after fix vs > 0 before.

## 9. Next steps

1. `/ck:plan` for increment 1 using this report as context (6 phases = 6 steps above).
2. Init git repo before first code.
3. After inc 1 done: brainstorm/plan increment 2.

## Unresolved questions

- Encryption key source for merchant secrets (env var vs file) — decide in plan, env var default.
- Payment expiry window default (15 min assumed).
- Whether mock-provider should also expose a "status query" endpoint in inc 1 or only in inc 4 (assume inc 4).
