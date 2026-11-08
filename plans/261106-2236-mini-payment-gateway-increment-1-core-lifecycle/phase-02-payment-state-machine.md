---
phase: 2
title: "Payment State Machine"
status: done
priority: P1
dependencies: [1]
effort: "1 day"
---

# Phase 2: Payment State Machine

## Overview
Create/get/cancel payments with an explicit state machine and a transitions audit table. No signature yet: the merchant is identified by the API key in `X-Merchant-Id`, looked up but not verified until phase 5. No provider yet (payment goes CREATED -> PENDING immediately with a placeholder pay URL). Minimal expiry sweeper so EXPIRED is reachable; it is a deliberate pull-forward from increment 4 (see plan.md "Deviations from brainstorm").

Red-team changes applied: #7 (UUID ids, every lookup scoped by merchant), #11 (sweeper isolated from tests, per-row transaction), #14 (no `currency` column), #15 (testable success criteria).

## Requirements
- Functional:
  - `POST /api/v1/payments` body `{orderId, amount (long, VND), description, returnUrl}` -> 201 `{paymentId, status, payUrl, expiresAt}`.
  - `GET /api/v1/payments/{id}` -> payment + list of transitions. Unknown id **or another merchant's id** -> 404 `PAYMENT_NOT_FOUND`.
  - `POST /api/v1/payments/{id}/cancel` -> CANCELLED only from PENDING, else 409 `PAYMENT_ILLEGAL_TRANSITION`. Same merchant scoping (404 for other merchants).
  - Expiry sweeper: every 30s move PENDING with `expires_at < now` to EXPIRED through the state machine, one row per transaction.
- Non-functional: every status change goes through one method; `payments.version` column exists now (used by increment 2); amount > 0 validated; sweeper disabled under the `test` profile.

## Architecture
```
payment/
  PaymentStatus (enum)            CREATED, PENDING, SUCCEEDED, FAILED, EXPIRED, CANCELLED
  PaymentStateMachine             Map<PaymentStatus, Set<PaymentStatus>> ALLOWED; transition(payment, to, reasonCode, source)
  Payment (entity)                id uuid, merchantId uuid, orderId, amount (long, VND), status, providerCode, providerRef, payUrl, expiresAt, @Version version, timestamps
  PaymentTransition (entity)      id, paymentId, fromStatus, toStatus, reasonCode, source (MERCHANT|PROVIDER|SYSTEM), createdAt
  PaymentRepository               findByIdAndMerchantId(id, merchantId); findOverdue(status, before)
  PaymentTransitionRepository
  PaymentOrchestrator (@Service)  create(), get(), cancel(), expireOverdue(); owns transaction boundaries
  PaymentExpirySweeper            @Scheduled(fixedDelay=30s), @ConditionalOnProperty(fluxpay.scheduling.enabled, default true; false in application-test.yml)
  PaymentController, dto/
merchant/
  MerchantResolver                looks up merchant by X-Merchant-Id (api key); 401 AUTH_UNKNOWN_MERCHANT if absent. Replaced by MerchantAuthFilter in phase 5.
```
Allowed transitions: CREATED->{PENDING, FAILED}; PENDING->{SUCCEEDED, FAILED, EXPIRED, CANCELLED}; terminal states -> none. `transition()` throws `IllegalPaymentTransitionException` (409) and appends a `PaymentTransition` row on success. `reasonCode` is a short stable token (`PROVIDER_ACCEPTED`, `MERCHANT_CANCEL`, `EXPIRED_BY_SWEEPER`, ...), never free text.

Invariant for the whole increment: **every read or write of a payment on `/api/**` goes through `findByIdAndMerchantId`.** There is no `findById` call in `payment/`.

`expireOverdue()` loads overdue ids, then processes each in its own transaction (`TransactionTemplate`, `REQUIRES_NEW`), catching and logging per row so one poison row cannot block the batch.

Flyway `V3__create_payments_and_transitions.sql`. **No unique (merchant_id, order_id) yet**: deliberately deferred to phase 6 so the duplicate-order demo is real.

Config: `fluxpay.payment.expiry: 15m` (demo uses 1m), `fluxpay.scheduling.enabled`.

Tests: `MutableClock` (`@TestConfiguration`, `@Primary`, `setInstant()`), registered in `AbstractIntegrationTest`; reset in `@AfterEach`. ITs call `orchestrator.expireOverdue()` directly instead of waiting for the scheduler.

## Related Code Files
- Create: `gateway/src/main/java/com/fluxpay/gateway/payment/**`, `merchant/MerchantResolver`, `V3__create_payments_and_transitions.sql`, `src/test/java/.../MutableClock`, `docs/learning/01-state-machine.md`, Postman folder "02 Payments".
- Modify: `common/ProblemDetailsHandler` (409, 404 mappings), `application.yml` + `application-test.yml` (expiry, scheduling flag), `AbstractIntegrationTest` (import `MutableClock`).
- Test: `PaymentStateMachineTest` (unit), `PaymentLifecycleIT` (Testcontainers).

## Implementation Steps
1. **Failure demo first (no state machine yet):** implement a naive version with `payment.setStatus(...)` directly in the service. Set expiry 1m. Create payment, wait for sweeper -> EXPIRED, then call a temporary `PATCH /api/v1/payments/{id}/status` with SUCCEEDED. Observe: EXPIRED overwritten by SUCCEEDED, no audit trail. Capture the responses in the note.
2. Write `docs/learning/01-state-machine.md` (VN): what a state machine is (states, events, transition table), the overwrite bug from step 1, why terminal states must be terminal, why an audit table matters for reconciliation later, why ids are UUIDs (no enumeration) and why every lookup carries the merchant id.
3. Implement `PaymentStatus`, `PaymentStateMachine` with the transition table and a single `transition()` entry point.
4. Entities + Flyway V3 + repositories (`findByIdAndMerchantId` only).
5. `MerchantResolver` + `PaymentOrchestrator.create()`: insert CREATED, transition to PENDING with placeholder `payUrl` (`"pending-provider"`), return DTO. `cancel()` and `expireOverdue()` go through the state machine. Remove the temporary PATCH endpoint.
6. `PaymentExpirySweeper` with `@EnableScheduling` + `@ConditionalOnProperty`; `MutableClock` test config.
7. Controller + DTOs + validation; 409/404 mappings in `ProblemDetailsHandler`.
8. Tests: unit test every allowed and every forbidden transition (parameterized); IT: create -> PENDING with one transition row; cancel -> CANCELLED; cancel again -> 409; GET and cancel with another merchant's api key -> 404; `MutableClock` +16 min then `expireOverdue()` -> EXPIRED.
9. Postman: create, get, cancel, cancel-again (expect 409), get with wrong merchant (404).
10. Note exercise ("Tự phá thử"): add a REFUNDED state to the enum without adding it to the transition table and watch the parameterized test fail; explain why that failure is desirable.

## Success Criteria
- [x] Failure demo recorded in note 01 (as explanation + locking tests; deviation noted in the note).
- [x] `grep -rn "\.setStatus(" gateway/src/main/java` matches only inside `PaymentStateMachine.java` (the setter itself lives on `Payment.java`, call sites do not). Note: grep also matches `ProblemDetailWriter.response.setStatus(...)`, an unrelated HttpServletResponse call from phase 1, not a payment-status mutation; the invariant holds.
- [x] `grep -rn "findById(" gateway/src/main/java/com/fluxpay/gateway/payment` returns nothing.
- [x] Unit + IT green without sleeps; Postman folder passes.

## Risk Assessment
- Sweeper and a later webhook may race on the same row -> acknowledged, that race is the increment 2 lesson; `@Version` column exists already.
- Temporary PATCH endpoint must be removed before phase 3 -> listed in step 5.
- Sweeper is early relative to the brainstorm roadmap -> recorded in plan.md deviations; increment 4 hardens it (batching, metrics), it is not re-built.

## Completion Notes (2026-11-07)
- Build green: gateway 42 unit tests (incl. 40 parameterized state-machine cases) + 1 integration test class (5 cases) via failsafe; mock-provider unchanged. `./mvnw verify` SUCCESS.
- Entities implement `Persistable<UUID>` (assigned-UUID ids) so Spring Data uses persist, not merge; without this, mutations after `save()` in `create()` would be dropped.
- Wired `maven-failsafe-plugin` in `gateway/pom.xml` so `*IT` run at `verify` (spring-boot-starter-parent manages the version but does not bind executions). This is the first integration test in the repo.
- `Duration` config `15m` binds via Boot's ApplicationConversionService on a plain `@Value`.
- Boot 4 / Jackson 3 and Testcontainers specifics already recorded in phase 1 notes.
- Deviation: failure demo delivered as explanation + locking tests (per project learning-mode), not a throwaway naive server. Recorded in `docs/learning/01-state-machine.md`.
