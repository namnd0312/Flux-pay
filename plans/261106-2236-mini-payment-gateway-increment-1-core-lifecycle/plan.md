---
title: "Flux-pay Increment 1: Core Payment Lifecycle (learning-first)"
description: "Backend-only mini payment gateway (VNPAY/MoMo style). Increment 1 builds the synchronous core: state machine, provider abstraction, mock provider, webhook de-dup, HMAC auth, idempotency. Every phase = failure demo -> concept -> fix -> verify, with a Vietnamese learning note."
status: done
priority: P2
branch: ""
tags: [payment-gateway, learning, spring-boot, postgres]
blockedBy: []
blocks: []
created: "2026-11-06T15:46:38.374Z"
createdBy: "ck:plan"
source: skill
---

# Flux-pay Increment 1: Core Payment Lifecycle (learning-first)

## Overview

Greenfield. Build the synchronous core of a mini payment gateway so the user can **learn from zero** how a gateway stays correct: payment lifecycle, provider abstraction, inbound webhooks, request signing, idempotency. No Kafka, no locking tuning, no reconciliation yet (increments 2-4).

Source of all decisions: `../reports/brainstorm-261106-2236-mini-payment-gateway-learning-roadmap-report.md`.

**Learning contract (applies to every phase):**
1. Reproduce the failure first (Postman / curl loop) and record what went wrong.
2. Explain the concept in `docs/learning/NN-<topic>.md` (Vietnamese prose, English terms).
3. Implement the fix. Claude writes all code with inline explanation.
4. Verify: Postman request + automated test (Testcontainers). Note ends with a "Tự phá thử" exercise.

## Stack (fixed)

- Java 21, **Spring Boot 4.1.x** (matches sibling `english-study/backend/pom.xml`; Boot 4 starter names: `spring-boot-starter-webmvc`, `spring-boot-starter-flyway`, `spring-boot-starter-data-jpa`, `spring-boot-starter-validation`, `spring-boot-starter-actuator`, **`spring-boot-starter-restclient`** (RestClient is not in the webmvc starter in Boot 4), test starters incl. `spring-boot-starter-restclient-test`, `spring-boot-testcontainers`, `testcontainers-postgresql`).
- Maven aggregator: `gateway/` (modular monolith, port 8080) + `mock-provider/` (fake VNPAY, port 8081).
- PostgreSQL 16 via Docker Compose, Flyway migrations (SQL only), RFC 7807 problem details with a stable `code` field, written by a shared `ProblemDetailWriter` from both the controller advice and the filters.
- No Spring Security in increment 1: HMAC auth is a plain `OncePerRequestFilter` (clearer to learn).
- Transactions: orchestration code uses `TransactionTemplate` for multi-step flows (create, webhook); `@Transactional` only on single-step service methods.
- Packages: `com.fluxpay.gateway.{common,merchant,payment,provider,webhook,idempotency}`, `com.fluxpay.mockprovider`.
- Shared test double: `CountingFakeProvider` (phase 3) replaces HTTP stubbing in every integration test; `MockRestServiceServer` only in the adapter unit test.

## Phases

| Phase | Name | Status |
|-------|------|--------|
| 1 | [Project Skeleton](./phase-01-project-skeleton.md) | Done |
| 2 | [Payment State Machine](./phase-02-payment-state-machine.md) | Done |
| 3 | [Provider Interface and Mock Provider](./phase-03-provider-interface-and-mock-provider.md) | Done |
| 4 | [Sync Webhook and Deduplication](./phase-04-sync-webhook-and-deduplication.md) | Done |
| 5 | [Merchant HMAC Auth](./phase-05-merchant-hmac-auth.md) | Done |
| 6 | [Idempotency](./phase-06-idempotency.md) | Done |

Strictly sequential: each phase depends on the previous one.

## Dependencies

- None cross-plan. Increments 2-4 (concurrency hardening, Kafka, resilience/reconciliation) will be separate plans that depend on this one.

## Deviations from brainstorm (recorded, not silent)

| Item | Brainstorm | Plan | Why |
|---|---|---|---|
| Expiry sweeper | Increment 4 | Phase 2 (minimal, per-row tx, off in tests) | EXPIRED must be reachable for the state-machine and late-webhook demos; increment 4 hardens, does not rebuild |
| Mock-provider knobs | delay, duplicate, out-of-order | delay, duplicates | Out-of-order and retry simulation move to increment 2 where the race tests need them |
| Secret storage | "encrypted at rest with local key" | Kept, simplified: SQL migration with precomputed ciphertext, random IV, dev key committed for tests/compose | User choice after red-team (finding 8) |
| Order uniqueness | unique (merchant_id, order_id) | Partial unique index on live statuses only | Red-team finding 1: failed orders must stay retriable |
| Idempotency wiring | explicit calls in controller | `IdempotencyFilter` with response wrapper | Red-team finding 5: controller never sees error responses rendered by the advice |

## Acceptance criteria (whole increment)

- [ ] `docker compose up --build` starts postgres + gateway + mock-provider; Postman collection runs the happy path end to end (create -> pay on mock -> webhook -> SUCCEEDED). *(Wiring complete: compose passes `FLUXPAY_SECRET_KEY` from `.env`; Postman auto-signs `/api`. Pending a manual compose run — `cp .env.example .env` first.)*
- [x] Illegal transition (e.g. webhook SUCCESS on EXPIRED) is rejected and recorded, never overwrites state. *(WebhookIngestionIT.lateWebhookOnExpiredIsRejected, PaymentStateMachineTest)*
- [x] Duplicate webhook (same event id) returns 200, leaves exactly one event row (`duplicate_count=1`) and one transition. *(WebhookIngestionIT.duplicateLeavesOneRowAndOneTransition)*
- [x] Unsigned / badly signed / stale (>5 min) / cross-endpoint-replayed merchant request is rejected with 401, never 500. *(MerchantAuthFilterIT — all five 401 codes + GET-replay-on-cancel + empty-secret; no 500 reachable)*
- [x] Merchant B cannot read or cancel merchant A's payment (404). *(PaymentLifecycleIT.anotherMerchantCannotSeeOrCancel, signed as merchant B)*
- [x] 50 concurrent identical create requests produce exactly 1 payment row and 1 provider call. *(ConcurrentCreateIT)*
- [x] An order that FAILED can be created again with a new idempotency key. *(IdempotencyIT.failedOrderIsRecreatableWithNewKey)*
- [x] Six learning notes exist in `docs/learning/`, each with a failure demo and a "Tự phá thử" exercise. *(00-gateway-anatomy … 05-idempotency)*
- [x] `./mvnw verify` green on a clean checkout (unit + Testcontainers integration tests, no env vars needed). *(Reactor BUILD SUCCESS: gateway 62 unit + 39 IT, mock-provider 4; test key comes from application-test.yml)*

## Open questions

- Default payment expiry: 15 min, overridable via `fluxpay.payment.expiry` (set to 1m for demos).
- CREATED timeout for abandoned creates: 2 min default (`fluxpay.payment.created-timeout`); adjust after phase 3 if the provider timeout changes.

## Red Team Review

### Session — 2026-11-06
**Reviewers:** Security Adversary, Failure Mode Analyst, Assumption Destroyer, Scope & Complexity Critic (38 raw findings, deduplicated to 15).
**Findings:** 15 (14 accepted, 1 rejected; finding 8 accepted as modified by user)
**Severity breakdown:** 2 Critical, 9 High, 4 Medium
**Reports:** `./reports/from-code-reviewer-to-planner-red-team-*-plan-review-report.md`

| # | Finding | Severity | Disposition | Applied To |
|---|---------|----------|-------------|------------|
| 1 | Unconditional unique (merchant_id, order_id) + stored 502 + terminal FAILED makes failed orders un-retriable | Critical | Accept: partial unique index on live statuses | Phase 6 |
| 2 | Single `@Transactional` webhook `process()` cannot catch unique violation / illegal transition; "one DUPLICATE row" impossible under unique key; unknown-payment insert before dedup | Critical | Accept: three `TransactionTemplate` steps, `ON CONFLICT DO NOTHING RETURNING`, one row + `duplicate_count` | Phase 4 |
| 3 | `RestClient`, `@RestClientTest`, mock-provider test deps not declared (verified against Boot 4.1.2 poms); provider test double undecided | High | Accept: restclient starters in phase 1; `CountingFakeProvider` shared | Phases 1, 3 |
| 4 | Two-transaction create via self-invocation collapses silently | High | Accept: `TransactionTemplate` + no-active-tx assertion | Phase 3 |
| 5 | Controller cannot store advice-rendered 502; `abandon()` orphans CREATED; IN_PROGRESS stuck 24h after crash; `REQUIRES_NEW` inert | High | Accept: filter-based storage, compensation CREATED->FAILED, 60s lease, plain `@Transactional` | Phases 3, 6 |
| 6 | HMAC omits method/path (GET signature replays on cancel); `Idempotency-Key` unsigned; "window blocks replays" false; String-based HMAC breaks on non-ASCII | High | Accept: canonical string, byte-based signer, reworded note | Phases 4, 5 |
| 7 | Cross-merchant cancel IDOR; scoping only for GET; id type unspecified | High | Accept: UUID ids, `findByIdAndMerchantId` everywhere, cancel test | Phases 2, 5 |
| 8 | Encrypted secrets: no IV spec, Flyway Java migration cannot inject beans, no test key, decrypt -> 500, not a learning goal | High | Accept (modified by user): keep encryption, simplified (SQL migration + precomputed ciphertext, random IV, dev key in tests/compose, fail-fast startup) | Phase 5 |
| 9 | Filter 401s have no body writer; filter exceptions bypass the advice | High | Accept: `ProblemDetailWriter` shared by advice and filters | Phases 1, 5, 6 |
| 10 | Header renamed `X-Merchant-Id` -> `X-Api-Key` mid-increment; `merchants.code` dead; Postman env drift | High | Accept: `X-Merchant-Id` carries api key throughout; drop `code` | Phases 1, 2, 5 |
| 11 | Sweeper pulled from increment 4; runs live in cached test contexts; poison row blocks batch | Medium | Accept partially: keep sweeper (recorded deviation), off in test profile, `MutableClock`, per-row tx | Phase 2 |
| 12 | Phase 4 too large; synchronous sender hangs pay request; retry simulator undecided; out-of-order missing | Medium | Accept: async 202 sender, cut retry, out-of-order -> increment 2, effort 1 day | Phase 4 |
| 13 | Burst demo: three signing tools, "fresh timestamp" impossible, fallback deletes demo | High | Accept: replay one pre-signed request, no shell signer | Phase 6 |
| 14 | Interface stubs throwing; provider exception text leaked; webhook lookup ignores provider code; unbounded body buffering; dead `signature_valid`/`currency`; config prefix drift | Medium | Accept all | Phases 2, 3, 4, 5 |
| 15 | Success criteria trivially true / impossible as written; no deviations record | Medium | Accept: rewritten criteria, deviations table above | Phases 2, 3, plan.md |
| R1 | Distinct 401 codes enable api-key enumeration | Medium | Reject: learning aid; production practice documented in note 04 | — |

### Whole-Plan Consistency Sweep
- Files reread: plan.md, phase-01 … phase-06 (after rewrite)
- Decision deltas checked: 12 (header name, UUID ids, `secret` column, restclient starters, `CountingFakeProvider`, `TransactionTemplate` flows, three-tx webhook + `duplicate_count`, canonical signature string, simplified encryption + dev key, partial unique index, `IdempotencyFilter` + lease, async mock sender / knobs)
- Reconciled stale references: all phase files rewritten; grep for 14 superseded terms (`X-Api-Key`, `signature_valid`, `currency`, `queryStatus`, `retry-on-non-200`, `sign.sh`, `BaseJavaMigration`, `merchants.code`, `secret_encrypted`, `mock.webhook.url`, `/mock/pay/`, `existsByProviderCodeAndEventId`, `APPLIED|DUPLICATE`, `Spring Boot 3`) returns only intentional hits (this log, `MerchantResolver` introduced in phase 2 and deleted in phase 5, per-row `REQUIRES_NEW` in the sweeper, "increment 4 adds queryStatus" comment)
- Flyway sequence V1..V7 and header names (`X-Merchant-Id`, `X-Timestamp`, `X-Signature`, `Idempotency-Key`, `X-Mock-Signature`) consistent across all files
- Unresolved contradictions: 0
