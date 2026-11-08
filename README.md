# Flux-pay

A mini payment gateway (VNPAY/MoMo style), backend only, built as a **learning-first** project. Every phase reproduces a real failure, explains the concept, fixes it, and verifies the fix. Learning notes live in [`docs/learning/`](docs/learning/) (Vietnamese prose, English technical terms).

This is **Increment 1: the synchronous core** — payment state machine, provider abstraction, mock provider, webhook de-duplication, HMAC auth, idempotency. Concurrency hardening, Kafka, and reconciliation come in later increments.

## Layout

```
flux-pay/
  gateway/         modular monolith, port 8080 (owns payment state + event ledger)
  mock-provider/   fake VNPAY-style provider, port 8081
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

## Learning notes

Start with [`docs/learning/00-gateway-anatomy.md`](docs/learning/00-gateway-anatomy.md).
