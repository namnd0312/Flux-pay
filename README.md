# Flux-pay

A mini payment gateway (VNPAY/MoMo style), backend only, built as a **learning-first** project. Every phase reproduces a real failure, explains the concept, fixes it, and verifies the fix. Learning notes live in [`docs/learning/`](docs/learning/) (Vietnamese prose, English technical terms).

**Increment 1** built the synchronous core: payment state machine, provider abstraction, mock provider, webhook de-duplication, HMAC auth, idempotency. **Increment 2** made it correct under concurrency (optimistic-lock retry, recoverable early webhooks, a row lock on the webhook path, a fenced idempotency lease) and added a load-test harness. Kafka and reconciliation come in later increments.

## Layout

```
flux-pay/
  gateway/         modular monolith, port 8080 (owns payment state + event ledger)
  mock-provider/   fake VNPAY-style provider, port 8081
  load-tests/      JMeter scenarios, runner, invariant checks
  postman/         collection + local environment
  docs/learning/   phase-by-phase learning notes
```

## Stack

- Java 21, Spring Boot 4.1.x, Maven multi-module (aggregator + 2 apps)
- PostgreSQL 16, Flyway migrations (SQL only)
- Testcontainers for integration tests

## Run

With Docker running, copy the dev env file first (it carries the secret-encryption key the gateway needs to start):

```bash
cp .env.example .env
docker compose up --build
```

Starts postgres, gateway (8080), and mock-provider (8081). Check health:

```bash
curl localhost:8080/actuator/health
curl localhost:8081/actuator/health
```

Run the tests (Docker required — Testcontainers starts a real Postgres):

```bash
./mvnw verify
```

Import `postman/flux-pay.postman_collection.json` and `postman/flux-pay.postman_environment.json` into Postman and run the **Phase 1 - Health** folder.

## Spring Boot 4 starter names

Boot 4 renamed and split several starters. Tutorials written for Boot 3 use the old names:

| Purpose | Boot 3 | Boot 4 (used here) |
|---|---|---|
| Web MVC | `spring-boot-starter-web` | `spring-boot-starter-webmvc` |
| Flyway | (transitive via jdbc) | `spring-boot-starter-flyway` + `flyway-database-postgresql` |
| RestClient | bundled in `-web` | `spring-boot-starter-restclient` (separate) |
| MVC test | `spring-boot-starter-test` | `spring-boot-starter-webmvc-test` |
| RestClient test | — | `spring-boot-starter-restclient-test` |

## Merchant authentication (phase 5)

Every `/api/**` request is HMAC-signed, VNPAY/MoMo style. The merchant sends:

- `X-Merchant-Id` — the api key (identifies the merchant)
- `X-Timestamp` — epoch millis (must be within 5 minutes of the gateway clock)
- `X-Signature` — `HMAC-SHA256(secret, canonical)` as lowercase hex

where `canonical` is `METHOD\nPATH\nX-Timestamp\nIdempotency-Key\nsha256hex(body)` (UTF-8).
`/webhooks/**` and `/actuator/health` are exempt. The Postman collection's **Phase 5 -
Auth** folder has a pre-request script that signs requests from the `merchantId` /
`merchantSecret` environment variables. See [`docs/learning/04-hmac-auth.md`](docs/learning/04-hmac-auth.md).

### Secret key

Merchant signing secrets are stored encrypted (AES-256-GCM) with a key from
`FLUXPAY_SECRET_KEY` (base64, 32 bytes). The gateway refuses to start if the key is unset,
malformed, or cannot decrypt the seeded DEMO secret. The committed dev key in `.env.example`
and `src/test/resources/application-test.yml` is **dev only — never reuse it in production.**

To rotate the key:

```bash
openssl rand -base64 32                 # new key -> FLUXPAY_SECRET_KEY
# re-encrypt each secret with the new key:
FLUXPAY_SECRET_KEY=<new-key> ./mvnw -q -pl gateway exec:java \
  -Dexec.mainClass=com.fluxpay.gateway.merchant.EncryptSecretTool \
  -Dexec.args="demo-merchant demo-secret-change-me"
# then add a new Flyway migration that UPDATEs merchants.secret with the printed v1:... value.
```

## Concurrency behaviour (increment 2)

**Webhook answers.** After a valid signature, `POST /webhooks/{provider}` answers:

- `200` for anything final: applied, duplicate, or a business rejection (`rejectReason` in the body);
- `503 WEBHOOK_RETRY_LATER` (with `Retry-After: 1` and a `reason`) when the same event may
  succeed later: the payment is still being created (`PAYMENT_NOT_READY`), the row is locked
  (`LOCK_TIMEOUT`), the payment write lost every retry (`CONCURRENT_UPDATE`), or another
  delivery of the event is still running (`EVENT_IN_PROGRESS`). The provider must send the
  same bytes again.

**Webhook row lock.** The webhook path reads the payment with `SELECT ... FOR NO KEY UPDATE`
and waits at most `fluxpay.webhook.lock-timeout` (default 2s) for the lock. An optimistic-only
variant was measured against it and removed; see
[`docs/learning/09-load-test-and-bottlenecks.md`](docs/learning/09-load-test-and-bottlenecks.md).

**Lost a race.** A payment write that loses a version race is tried up to 3 times in total
(2 retries); the caller then gets its business answer (for example `409 PAYMENT_ILLEGAL_TRANSITION`).

## Load tests

JMeter 5.6+ on the PATH, the compose stack running with the metrics override:

```bash
# start (or restart) the stack with metrics; the race scenario needs the mock to delay its webhook
MOCK_CALLBACK_DELAY_MS=100 docker compose -f docker-compose.yml -f load-tests/docker-compose.loadtest.yml up -d --build

METRICS=1 load-tests/run.sh webhook-vs-cancel -Jthreads=50 -Jloops=20
METRICS=1 load-tests/run.sh baseline-create-pay-poll -Jthreads=50 -Jduration=30
load-tests/run.sh same-key-burst -Jthreads=20 -Jloops=20
```

Each run writes a JMeter HTML report under `load-tests/results/`, waits for the system to
settle, and checks `load-tests/verify-invariants.sql`. The run fails if any JMeter sample
failed, if it created no payment, if any `HARD` invariant is not 0, or (with `METRICS=1`)
if the mock dropped a webhook. The invariants cover the whole database: reset it between
runs (`docker compose ... down -v`, or truncate the payment tables).

The override publishes unauthenticated metrics on `127.0.0.1:9090` (gateway) and `:9091`
(mock) only; do not use it as the default stack. With it, `/actuator/*` (health included)
moves to those ports: `curl 127.0.0.1:9090/actuator/health`. `docker-compose.yml` itself is
a local, untracked file.

## Learning notes

Start with [`docs/learning/00-gateway-anatomy.md`](docs/learning/00-gateway-anatomy.md).
