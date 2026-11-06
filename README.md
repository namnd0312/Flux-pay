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

With Docker running:

```bash
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

## Learning notes

Start with [`docs/learning/00-gateway-anatomy.md`](docs/learning/00-gateway-anatomy.md).
