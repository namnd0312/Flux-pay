---
phase: 3
title: "Provider Interface and Mock Provider"
status: done
priority: P1
dependencies: [2]
effort: "1 day"
---

# Phase 3: Provider Interface and Mock Provider

## Overview
Introduce the `PaymentProvider` interface (Strategy), a `MockProviderAdapter` (Adapter) over HTTP, and a `ProviderRegistry` (Factory). The mock-provider app gains a "create payment session" endpoint and returns a real pay URL + provider reference. Payment creation now calls the provider between two real transactions, and CREATED -> FAILED becomes reachable when the provider is down.

Red-team changes applied: #3 (one shared provider test double), #4 (two transactions via `TransactionTemplate`, verified by test), #5 (compensation for orphaned CREATED rows), #14 (interface carries only what this phase uses; stable reason codes, no exception text leaked), #15 (testable criteria).

## Requirements
- Functional:
  - mock-provider `POST /mock/v1/payments` body `{merchantRef, amount, description, returnUrl}` -> `{providerRef, payUrl}`; sessions kept in a `ConcurrentHashMap`. `merchantRef` = gateway payment id.
  - mock-provider `GET /mock/v1/payments/{providerRef}` -> session (status NEW). The pay action comes in phase 4.
  - gateway `create()` resolves provider by code (request field `provider`, default from `fluxpay.providers.default=MOCK`, resolved inside `provider/`), calls `createPayment`, stores `providerRef` + `payUrl`, transitions to PENDING. Provider error -> transition CREATED -> FAILED with reason `PROVIDER_UNAVAILABLE`, respond 502 `PROVIDER_UNAVAILABLE`.
  - Any other exception after CREATED is committed -> compensating transition CREATED -> FAILED with reason `INTERNAL_ERROR`, respond 500. Sweeper additionally fails CREATED rows older than `fluxpay.payment.created-timeout` (default 2m) with reason `CREATE_ABANDONED` (covers JVM death between the two transactions).
- Non-functional: Payment core has zero compile-time dependency on the mock adapter class; adding a provider = one new class + config. HTTP client timeouts 2s connect / 5s read. **No DB transaction is active while the provider HTTP call runs** (asserted by test).

## Architecture
```
provider/
  PaymentProvider (interface)
      String code();
      ProviderPaymentResult createPayment(ProviderPaymentRequest req);
      // phase 4 adds: verifyWebhookSignature(rawBodyBytes, headers), parseWebhook(rawBodyBytes)
      // increment 4 adds: queryStatus(providerRef)
  ProviderPaymentRequest / ProviderPaymentResult (records)
  ProviderRegistry           Map<String, PaymentProvider> from List<PaymentProvider> beans; get(code) -> UnknownProviderException (400 UNKNOWN_PROVIDER); defaultCode() from config
  ProviderException          wraps transport errors; carries a stable reasonCode; original message goes to logs only
  mock/MockProviderAdapter   @Component, RestClient (from RestClient.Builder) to ${fluxpay.providers.mock.base-url}
  mock/MockProviderProperties (@ConfigurationProperties)
payment/PaymentOrchestrator.create():
  tx1 = txTemplate.execute(insert CREATED)            // commit
  result = provider.createPayment(...)                 // no tx
  tx2 = txTemplate.execute(set providerRef/payUrl, transition PENDING)
  on ProviderException -> txTemplate.execute(transition FAILED, PROVIDER_UNAVAILABLE) -> rethrow (502)
  on any other exception -> txTemplate.execute(transition FAILED, INTERNAL_ERROR) -> rethrow (500)
mock-provider app:
  session/PaymentSession (record), PaymentSessionStore (ConcurrentHashMap), SessionController
test (gateway):
  CountingFakeProvider        @TestConfiguration @Primary PaymentProvider, code "MOCK": records calls, returns fixed providerRef/payUrl, can be told to throw ProviderException. Registered in AbstractIntegrationTest. Used by every IT from here on (phases 4, 6).
```
`create()` is **not** annotated `@Transactional`; it uses an injected `TransactionTemplate` for each step. Teach why: a proxy on the same bean would silently collapse the two transactions, and a network call inside a DB transaction holds a pooled connection for the full HTTP timeout (the bottleneck increment 2 will measure).

## Related Code Files
- Create: `gateway/.../provider/**`, `mock-provider/.../session/**`, `gateway/src/test/.../CountingFakeProvider`, `docs/learning/02-provider-abstraction.md`, Postman folder "03 Provider".
- Modify: `payment/PaymentOrchestrator` (two-transaction create, compensation), `payment/PaymentExpirySweeper` (CREATED timeout), `payment/dto` (add `provider` field), `common/ProblemDetailsHandler` (502, 500, 400 mappings), `application.yml` + `application-test.yml` (`fluxpay.providers.mock.base-url`, `fluxpay.providers.default`, `fluxpay.payment.created-timeout`), `docker-compose.yml` (gateway env points to mock-provider service), `AbstractIntegrationTest` (import `CountingFakeProvider`). No pom changes (restclient starters were added in phase 1).
- Test: `ProviderRegistryTest` (unit), `MockProviderAdapterTest` (`@RestClientTest` + `MockRestServiceServer`, the only place HTTP is stubbed), `PaymentCreateWithProviderIT` (uses `CountingFakeProvider`: happy path; provider throws -> FAILED + 502; fake asserts `TransactionSynchronizationManager.isActualTransactionActive() == false` during the call), `SessionControllerTest` in mock-provider (`@WebMvcTest`).

## Implementation Steps
1. **Failure demo first:** hard-code a call to the mock-provider URL inside a `@Transactional create()` with the provider logic inline. Then ask: "add VNPAY tomorrow, what changes?" Show the diff would touch the orchestrator, DTOs and tests. Also stop mock-provider and create a payment: observe the request taking the full HTTP timeout while holding a DB connection (watch Hikari active count in actuator metrics), and the payment stuck in CREATED forever.
2. Write `docs/learning/02-provider-abstraction.md` (VN): Strategy (choose behaviour by provider code), Adapter (translate provider API to our interface), Factory/registry (resolve by code), why the core must not know concrete providers, why network calls live outside DB transactions and how self-invocation defeats `@Transactional`, why a CREATED row needs a compensating path. Include a diagram of the redirect flow with payUrl + providerRef.
3. Define `PaymentProvider` with `code()` + `createPayment()` + records. Nothing else yet.
4. `ProviderRegistry` built from all `PaymentProvider` beans; duplicate code -> fail at startup; default from config.
5. `MockProviderAdapter` with `RestClient`, timeouts, maps transport errors to `ProviderException(PROVIDER_UNAVAILABLE)`; logs the cause.
6. mock-provider: session store + controller. Pay URL format `http://localhost:8081/mock/v1/pay/{providerRef}`.
7. Refactor `PaymentOrchestrator.create()` into the `TransactionTemplate` flow with both compensation paths; sweeper gains the CREATED timeout predicate.
8. `CountingFakeProvider` + tests listed above.
9. Postman: create with provider MOCK -> inspect payUrl; create with provider FOO -> 400; stop mock-provider -> 502 and payment FAILED with reason `PROVIDER_UNAVAILABLE`.
10. Exercise ("Tự phá thử"): write a `FakeBankAdapter` that always fails, register it, create a payment with `provider=FAKEBANK`, confirm nothing in `payment/` changed. Then move the `TransactionTemplate` calls back into one `@Transactional` method and watch the transaction-active assertion fail.

## Success Criteria
- [x] `grep -rni "mock" gateway/src/main/java/com/fluxpay/gateway/payment` returns nothing (default provider code lives in config + `provider/`).
- [x] `PaymentCreateWithProviderIT` proves no active transaction during `createPayment`.
- [x] Payment with provider down ends FAILED with reason `PROVIDER_UNAVAILABLE`, response 502, no exception text in the response body.
- [x] Unit + IT green; Postman folder passes.

## Risk Assessment
- JVM death between tx1 and tx2 leaves CREATED -> covered by the sweeper's CREATED timeout (reason `CREATE_ABANDONED`); increment 4 reconciliation can later query the provider for such rows.
- `@RestClientTest` artifact is `spring-boot-starter-restclient-test` in Boot 4.1 (verified against the local Maven cache during red-team); declared in phase 1.

## Completion Notes (2026-11-07)
- Build green: gateway 47 unit + 8 integration tests; mock-provider 3 tests. `./mvnw verify` SUCCESS.
- Boot 4.1 test-slice packages differ from Boot 3: `@RestClientTest` is `org.springframework.boot.restclient.test.autoconfigure.RestClientTest` (not `.web.client`); `@WebMvcTest` is `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest` (not `.web.servlet`).
- HTTP timeouts set via `spring.http.client.{connect,read}-timeout` (global), not a per-adapter request-factory override, so `@RestClientTest`'s MockRestServiceServer still intercepts. Adapter builds its client from `builder.clone().baseUrl(...)`.
- Real `MockProviderAdapter` is `@Profile("!test")`; `CountingFakeProvider` (@Primary, code MOCK) is the only MOCK provider under the test profile, avoiding a duplicate-code clash in the registry.
- `create()` is two `TransactionTemplate` steps around a no-transaction provider call; compensation paths cover provider failure (502) and unexpected errors (500); sweeper fails abandoned CREATED rows (`CREATE_ABANDONED`).
- Deviation: failure demo delivered as the CountingFakeProvider no-active-transaction assertion plus tests, not a throwaway naive `@Transactional` version. Recorded in `docs/learning/02-provider-abstraction.md`.
