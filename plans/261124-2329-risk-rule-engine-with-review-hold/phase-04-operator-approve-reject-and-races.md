---
phase: 4
title: "Operator approve reject and races"
status: completed
priority: P1
dependencies: [3]
effort: "2 sessions"
---

# Phase 4: Operator approve reject and races

## Overview

Give an operator a way to end the hold: approve (payment goes to the provider and becomes `PENDING`) or reject (`FAILED`). Then break it with concurrency: double approve, approve vs merchant cancel, approve vs review timeout, and the sweeper killing a just-approved payment. Learning note `13-operator-approval-and-claim-step.md`.

## Requirements
- Functional: `GET /internal/reviews`, `POST /internal/reviews/{paymentId}/approve`, `POST /internal/reviews/{paymentId}/reject`.
- Non-functional: exactly one provider call per approved payment under any interleaving; no provider call inside a transaction; losers of a race get 409, never 5xx; `/internal/*` unreachable without the admin token.

## Failures reproduced first
1. Naive approve (read status, call provider, then write `PENDING`): N concurrent approves -> N provider sessions.
2. Approve a payment held for more than `created-timeout`: once it is back in `CREATED`, `failAbandonedCreated()` (keyed on `created_at`) fails it mid-approval.
3. Approve at minute 29 of a 30m hold without resetting `expires_at`: the customer gets a pay URL that expires almost at once.

## Architecture
```
approve(paymentId):
  claim tx (OptimisticRetry) {
     load; transition UNDER_REVIEW -> CREATED (REVIEW_APPROVED, OPERATOR)
     expiresAt = now + fluxpay.payment.expiry }
  provider.createPayment(...)                      // no transaction
  tail tx { set providerRef/payUrl; CREATED -> PENDING }   // same code as create()
  provider failure -> compensateToFailed (existing)

reject(paymentId):
  tx { transition UNDER_REVIEW -> FAILED (RISK_REVIEW_REJECTED, OPERATOR) }
```
- The claim is the single-winner step: a second approve, a cancel or a timeout that lost finds `CREATED`/terminal and gets `IllegalPaymentTransitionException` -> 409. `@Version` + `OptimisticRetry` resolve the simultaneous case.
- Extract the provider call + tail from `create()` into one private method used by both paths (no duplicated compensation logic).
- `failAbandonedCreated()` switches to `updated_at`. For a normal create `updated_at == created_at` while `CREATED`, so existing behaviour is unchanged; a crash after the claim is still cleaned up after `created-timeout`.
- State machine: add `UNDER_REVIEW -> CREATED`. Cancel during the short `CREATED` window keeps today's behaviour (409).
- Lookups here are by payment id without merchant scope; they exist only behind the admin filter.
- `AdminTokenFilter` on `/internal/*`: header `X-Admin-Token`, `MessageDigest.isEqual` on bytes, 401 problem detail otherwise. `AdminTokenStartupCheck` refuses to start on a blank token (pattern of `SecretCipherStartupCheck`).
- `TransitionSource.OPERATOR` added.

## Related Code Files
- Create: `payment/PaymentReviewController.java`, `payment/dto/ReviewItemResponse.java`, `common/AdminTokenFilter.java`, `common/AdminTokenFilterConfig.java`, `common/AdminTokenStartupCheck.java`
- Modify: `payment/PaymentOrchestrator.java` (`approveReview`, `rejectReview`, `listUnderReview`, shared tail, sweeper query), `payment/PaymentRepository.java` (abandoned query on `updatedAt`, list by status), `payment/PaymentStateMachine.java`, `payment/TransitionSource.java`, `application.yml` (`fluxpay.admin.token: ${FLUXPAY_ADMIN_TOKEN:}`), `gateway/src/test/resources/application-test.yml` (dev token), local `.env.example`
- Modify tests: `payment/PaymentStateMachineTest.java`
- Create tests: `payment/ReviewApproveRejectIT.java`, `payment/ReviewApproveRaceIT.java`, `common/AdminTokenFilterIT.java`
- Modify docs: `README.md` (review flow, admin token, contract changes), `docs/system-architecture.md` (risk package, new state, internal API)
- Create: `docs/learning/13-operator-approval-and-claim-step.md`; Postman folder "Phase 13 - Review"

## Implementation Steps
1. `AdminTokenFilter` + startup check + `AdminTokenFilterIT` (missing, wrong, correct token; valid merchant HMAC headers without token -> 401; `/api/*` unaffected).
2. Write the naive approve, reproduce failure 1 with a latch-based IT against `CountingFakeProvider`; keep the numbers for the note.
3. Replace with claim + shared tail; add `UNDER_REVIEW -> CREATED` and `OPERATOR`.
4. Reproduce failures 2 and 3 with `MutableClock`; switch the abandoned query to `updated_at`; reset `expires_at` in the claim.
5. `rejectReview`, `listUnderReview` (id, merchant, order, amount, hold reason from the ledger, created and deadline times).
6. `ReviewApproveRejectIT`: approve -> `PENDING`, one provider call, fresh `expires_at`, ledger `CREATED -> UNDER_REVIEW -> CREATED -> PENDING`; then a success webhook -> `SUCCEEDED`; reject -> `FAILED` and the order can be created again; approve on a non-held payment -> 409; unknown id -> 404; provider down on approve -> `FAILED` + 502.
7. `ReviewApproveRaceIT` (same latch style as `WebhookVsCancelRaceIT`): 10 concurrent approves -> one provider call, one 200, rest 409; approve vs cancel; approve vs sweeper timeout; approve vs reject. Each asserts one final state and a ledger with no impossible edge.
8. Update README and `docs/system-architecture.md`; `./mvnw verify`.
9. Note 13: why approve needs a claim before the HTTP call, how it mirrors the two-transaction create, why the sweeper column mattered, limits of a shared admin token.

## Success Criteria
- [x] Acceptance 7, 8, 9, 10 pass
- [x] Race IT stable over 20 consecutive runs
- [x] No provider call inside a transaction (code review of `approveReview`)
- [x] README and architecture doc match the shipped behaviour; note 13 written

## Risk Assessment
- `updated_at` semantics: anything else that touches a `CREATED` row would extend its abandon window; today only the state machine calls `touch`. The existing abandoned-row tests must stay green without edits.
- Crash between claim and provider call leaves `CREATED`; sweeper fails it after `created-timeout`; the merchant sees `FAILED` and may re-create. Accepted, stated in the note.
- Shared admin token: no per-operator audit. Ledger records `OPERATOR` only. Out of scope by decision.
- Kafka plan's webhook consumer and notification hook were designed without this flow: re-read that plan before starting it (already listed as blocked).
