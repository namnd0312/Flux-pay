---
phase: 1
title: "Project Skeleton"
status: done
priority: P1
dependencies: []
effort: "0.5 day"
---

# Phase 1: Project Skeleton

## Overview
Runnable Maven aggregator with two Spring Boot 4.1 apps, PostgreSQL via Docker Compose, Flyway with the `merchants` table and one seeded demo merchant, health endpoints, Testcontainers test base, Postman collection skeleton, and learning note 00 explaining the actors of a payment gateway.

Red-team changes applied: #3 (declare restclient starters now), #10 (`X-Merchant-Id` carries the API key for the whole increment, no `merchants.code`).

## Requirements
- Functional: `GET /actuator/health` on 8080 (gateway) and 8081 (mock-provider) return UP; Flyway creates `merchants`.
- Non-functional: `./mvnw verify` runs a Testcontainers context-load test; no secrets committed except the documented dev key placeholder (phase 5); `.gitignore` covers Maven build output, `.env`, IDE files.

## Architecture
```
Flux-pay/
  pom.xml                      # aggregator: modules gateway, mock-provider
  .mvn/ mvnw mvnw.cmd          # copy from sibling english-study/backend
  docker-compose.yml           # postgres:16, gateway, mock-provider
  gateway/  pom.xml, Dockerfile, src/main/java/com/fluxpay/gateway/{GatewayApplication, common/}
            src/main/resources/{application.yml, db/migration/V1__create_merchants.sql, V2__seed_demo_merchant.sql}
            src/test/resources/application-test.yml
            src/test/java/com/fluxpay/gateway/{AbstractIntegrationTest, ContextLoadsTest}
  mock-provider/ pom.xml, Dockerfile, src/main/java/com/fluxpay/mockprovider/MockProviderApplication
  postman/flux-pay.postman_collection.json, flux-pay.postman_environment.json
  docs/learning/00-gateway-anatomy.md
  README.md
```
`merchants` table: `id uuid pk, name, api_key varchar unique, secret text, active bool, created_at`. The API key is the public merchant identifier sent in `X-Merchant-Id` from phase 2 onward. Seed one merchant `DEMO` (V2) with api key `demo-merchant` and a plaintext secret; phase 5 replaces the plaintext with ciphertext (column stays `secret`).

`common/` holds `ProblemDetailsHandler` (`@RestControllerAdvice`, RFC 7807 with `code` field), `ProblemDetailWriter` (builds + writes a problem detail to a raw `HttpServletResponse`; shared by the advice and by filters in phases 5-6) and a `Clock` bean (overridden by `MutableClock` in tests from phase 2).

## Related Code Files
- Create: everything above. Reference for pom structure: `/Users/admin/Desktop/DEV/AI/english-study/backend/pom.xml` (Spring Boot 4.1.2 starters, Testcontainers).
- Modify: none.

## Implementation Steps
1. `git init`, `.gitignore`, root `pom.xml` (packaging pom, modules gateway + mock-provider, Java 21, Boot parent 4.1.x).
2. `gateway/pom.xml`: `spring-boot-starter-webmvc`, `spring-boot-starter-data-jpa`, `spring-boot-starter-validation`, `spring-boot-starter-flyway` + `flyway-database-postgresql`, `spring-boot-starter-actuator`, **`spring-boot-starter-restclient`** (Boot 4 splits `RestClient` out of the webmvc starter), `postgresql`; test: `spring-boot-starter-webmvc-test`, `spring-boot-starter-data-jpa-test`, **`spring-boot-starter-restclient-test`**, `spring-boot-testcontainers`, `testcontainers-postgresql`, `testcontainers-junit-jupiter`.
3. `application.yml`: datasource from env (`DB_URL`, `DB_USER`, `DB_PASSWORD`), `spring.jpa.open-in-view=false`, `spring.jpa.hibernate.ddl-auto=validate`, actuator health exposed. `src/test/resources/application-test.yml` with `fluxpay.*` test defaults (grows in later phases); `AbstractIntegrationTest` uses `@ActiveProfiles("test")`.
4. Flyway V1 + V2 as above. Explain in note why Flyway (schema is a contract, `ddl-auto=validate` catches drift).
5. `mock-provider/pom.xml`: `spring-boot-starter-webmvc`, `spring-boot-starter-actuator`, **`spring-boot-starter-restclient`**; test: **`spring-boot-starter-webmvc-test`**, **`spring-boot-starter-restclient-test`**. Empty app on 8081.
6. `docker-compose.yml`: postgres with healthcheck, gateway and mock-provider built from Dockerfiles (multi-stage, `eclipse-temurin:21-jre`), env wiring, `depends_on` with condition.
7. `AbstractIntegrationTest`: `@SpringBootTest` + `@ServiceConnection` PostgreSQL container + `@ActiveProfiles("test")`. `ContextLoadsTest` asserts health 200.
8. Postman collection with folder structure for all phases and env vars `gatewayUrl`, `mockUrl`, `merchantId` (= api key `demo-merchant`), `merchantSecret`.
9. `README.md`: how to run (`docker compose up --build`, `./mvnw verify`), ports, Boot 3 vs Boot 4 starter-name mapping (webmvc, flyway, restclient), link to `docs/learning/`.
10. `docs/learning/00-gateway-anatomy.md` (VN): actors (merchant, gateway, provider/bank, customer), VNPAY redirect flow (pay URL -> IPN), why a gateway is "a state machine + a ledger of events", map of the 6 phases to real gateway responsibilities. No failure demo in this phase; exercise: draw the sequence diagram for a MoMo payment from memory.

## Success Criteria
- [x] `docker compose up --build` -> both health endpoints UP, Flyway applied V1, V2.
- [x] `./mvnw verify` green with Testcontainers (Docker required).
- [x] `RestClient.Builder` bean resolvable in both apps (asserted in `ContextLoadsTest`).
- [x] `postman/` imports cleanly; health requests pass.
- [x] `docs/learning/00-gateway-anatomy.md` written in Vietnamese with English terms.

## Completion Notes (2026-11-06)
- Boot 4.1.2 ships Jackson 3: `ObjectMapper` is `tools.jackson.databind.ObjectMapper`, not `com.fasterxml.jackson.databind`.
- Testcontainers `org.testcontainers.postgresql.PostgreSQLContainer` is non-generic (no `<SELF>` type parameter), unlike the older `org.testcontainers.containers` class.
- All Boot 4.1 starter names confirmed on Maven Central: `spring-boot-starter-{webmvc,flyway,restclient,webmvc-test,restclient-test}`.
- `./mvnw verify`: gateway 2/2 tests, mock-provider 1/1 test, Flyway applied V1+V2 on Postgres 16.

## Risk Assessment
- Boot 4.1 starter names differ from Boot 3 tutorials the user may read -> mapping in README.
- Testcontainers needs Docker running -> stated in README; tests fail fast with a clear message otherwise.
