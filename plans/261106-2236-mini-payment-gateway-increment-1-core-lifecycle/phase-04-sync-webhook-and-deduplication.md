---
phase: 4
title: "Sync Webhook and Deduplication"
status: done
priority: P1
dependencies: [3]
effort: "1 day"
---

# Phase 4: Sync Webhook and Deduplication

## Overview
Close the loop: the mock-provider "customer pays" endpoint sends a signed webhook (IPN) over real HTTP to the gateway; the gateway verifies the signature, records the event exactly once, applies the state transition synchronously and answers 200. Duplicate and late webhooks are handled explicitly. This is the first place at-least-once delivery bites.

Red-team changes applied: #2 (three explicit transactions, one row per event with a duplicate counter, dedup before any other write), #12 (async sender, retry simulator and out-of-order moved to increment 2, effort 1 day), #14 (lookup by provider code + ref, merchantRef check, body size cap, no `signature_valid` column, one `mock.*` config prefix).

## Requirements
- Functional:
  - mock-provider `POST /mock/v1/pay/{providerRef}?outcome=SUCCESS|FAILED` -> marks session, returns **202 immediately**, and asynchronously (dedicated executor) sends webhook `{eventId, providerRef, merchantRef, status, amount, occurredAt}` signed to `${mock.gateway-webhook-url}/webhooks/MOCK`. Config: `mock.secret`, `mock.gateway-webhook-url`, `mock.callback.delay-ms` (default 0), `mock.callback.duplicates` (default 0, number of extra identical sends, same eventId). Sender timeouts 2s connect / 5s read; a failed send is logged, not retried (retry and out-of-order simulation belong to increment 2).
  - gateway `POST /webhooks/{providerCode}`: body cap 64 KB (413 above), resolve provider (404 unknown code), verify signature (401 `WEBHOOK_BAD_SIGNATURE`), parse, insert-or-detect-duplicate, then classify and apply. Response always 200 `{received:true, duplicate:boolean, applied:boolean, rejectReason?}` after a valid signature.
  - Rejections stored on the event row with `processing_status=REJECTED` and `reject_reason` in {`UNKNOWN_PAYMENT`, `REF_MISMATCH`, `AMOUNT_MISMATCH`, `ILLEGAL_TRANSITION`}.
- Non-functional: webhook endpoint never returns 4xx/5xx for business rejections (only 401/404/413 before any write), so a real provider stops retrying. Raw body stored verbatim. Exactly one `webhook_events` row per `(provider_code, event_id)`.

## Architecture
```
webhook/
  WebhookController            POST /webhooks/{providerCode}; reads raw body as byte[] (signature must cover exact bytes); enforces 64 KB cap
  WebhookIngestionService      NOT @Transactional. Orchestrates three TransactionTemplate steps.
  WebhookEvent (entity)        id, providerCode, eventId, paymentId (nullable), rawBody (text), processingStatus (RECEIVED|APPLIED|REJECTED), rejectReason, duplicateCount int, receivedAt, processedAt
  WebhookEventRepository       native: INSERT ... ON CONFLICT (provider_code, event_id) DO NOTHING RETURNING id; native: UPDATE ... SET duplicate_count = duplicate_count + 1
provider/PaymentProvider      += verifyWebhookSignature(byte[] rawBody, Map<String,String> headers), parseWebhook(byte[] rawBody) -> ProviderWebhookEvent
provider/mock/MockProviderAdapter   implements both: HMAC-SHA256(secret, rawBody) lowercase hex vs header X-Mock-Signature (MessageDigest.isEqual)
mock-provider:
  pay/PayController (202), webhook/WebhookSender (@Async on "webhookExecutor", RestClient, signing, delay, duplicates)
```
Processing order inside `process()` (each numbered tx is its own `TransactionTemplate.execute`):
1. verify signature (no tx) -> 401 if invalid. Parse -> `ProviderWebhookEvent`.
2. **tx A:** insert event row (status RECEIVED) with `ON CONFLICT DO NOTHING RETURNING id`. No id returned -> it is a duplicate: **tx A':** increment `duplicate_count`, return 200 `{duplicate:true, applied:false}`. The unique constraint is the only arbiter; there is no exists-check.
3. **tx B:** load payment by `findByProviderCodeAndProviderRef`; checks in order: payment exists (else `UNKNOWN_PAYMENT`), `event.merchantRef == payment.id` (else `REF_MISMATCH`), amount equal (else `AMOUNT_MISMATCH`); then `stateMachine.transition(payment, SUCCEEDED|FAILED, PROVIDER_WEBHOOK, PROVIDER)`. `IllegalPaymentTransitionException` is caught **outside** tx B (the template rolls back only tx B).
4. **tx C:** update the event row to APPLIED, or REJECTED + reason. Return 200 `{duplicate:false, applied:boolean, rejectReason}`.

Why not one transaction: a unique violation aborts a PostgreSQL transaction and a runtime exception crossing a `@Transactional` boundary marks it rollback-only, so "catch and continue" inside one tx commits nothing and returns 500. Teach this explicitly in the note.

Signature: `X-Mock-Signature: lowercasehex(HMAC-SHA256(providerSecret, rawBodyBytes))`. Shared secret `fluxpay.providers.mock.webhook-secret` on gateway = `mock.secret` on mock-provider. `HmacSigner` (byte-based) is introduced here in `common/` and reused by phase 5.

Flyway `V4__create_webhook_events.sql`.

## Related Code Files
- Create: `gateway/.../webhook/**`, `common/HmacSigner`, `V4__create_webhook_events.sql`, mock-provider `pay/**`, `webhook/**`, `AsyncConfig` (executor), `docs/learning/03-webhook-ipn.md`, Postman folder "04 Webhook".
- Modify: `provider/PaymentProvider` (+2 methods), `provider/mock/MockProviderAdapter`, `payment/PaymentRepository` (`findByProviderCodeAndProviderRef`), `CountingFakeProvider` (implements the two new methods with a test secret), `application.yml` both apps, `application-test.yml`, `docker-compose.yml` (`MOCK_GATEWAY_WEBHOOK_URL=http://gateway:8080`), `common/ProblemDetailsHandler` (401, 413 mappings).
- Test: `HmacSignerTest` (unit; includes a non-ASCII body vector computed with `openssl dgst -sha256 -hmac`), `MockProviderAdapterSignatureTest` (unit), `WebhookIngestionIT` (valid -> SUCCEEDED; same event twice -> one row, `duplicate_count=1`, one transition; bad signature -> 401, no row; amount mismatch -> REJECTED; wrong merchantRef -> REJECTED; webhook on EXPIRED -> REJECTED `ILLEGAL_TRANSITION`, status unchanged; unknown providerRef twice -> one REJECTED row + duplicate_count, both 200), `WebhookSenderTest` in mock-provider (`MockRestServiceServer`: duplicates count, eventId constant across sends, signature header present).

## Implementation Steps
1. **Failure demo first:** implement the naive endpoint (parse JSON DTO, transition inside one `@Transactional`, no event table, no signature). Then:
   a. Set `mock.callback.duplicates=1`. Pay once -> second webhook hits the state machine -> 409 -> gateway log shows an error for a payment that is already SUCCEEDED. Explain: a real provider treats non-200 as "not delivered" and retries for hours.
   b. Send a hand-crafted webhook from Postman with `status=SUCCESS` for a payment that never happened at the provider -> payment SUCCEEDED. Nobody paid.
   c. Send a valid webhook with a tampered amount (1,000 VND for a 1,000,000 VND order) -> SUCCEEDED.
2. Write `docs/learning/03-webhook-ipn.md` (VN): what an IPN is, at-least-once delivery and why providers retry, event id as the dedup key and why the DB constraint (not an exists-check) decides, "always 200 for business rejections", signature over raw bytes (why a `@RequestBody` DTO breaks it), amount and merchantRef verification, why three transactions instead of one, the three demos above.
3. Flyway V4, entity, repository natives.
4. `HmacSigner` (byte[] in, lowercase hex out, constant-time verify) + `MockProviderAdapter.verifyWebhookSignature` / `parseWebhook`.
5. `WebhookIngestionService.process()` in the order above; `WebhookController` reads `byte[]` with the cap.
6. mock-provider `PayController` (202) + async `WebhookSender` with delay/duplicates config and timeouts.
7. Remove the naive endpoint. Wire compose env.
8. Tests listed above.
9. Postman: happy path create -> pay -> get (SUCCEEDED); duplicate send; forged signature (401); tampered amount (REJECTED); late webhook after cancel (REJECTED).
10. Exercise ("Tự phá thử"): set `mock.callback.delay-ms=70000` with expiry 1m; the pay request returns 202 at once; observe the sweeper expiring the payment and then the late SUCCESS webhook being REJECTED and stored. Ask: what should a real gateway do with that money? (answer in increment 4: reconciliation).

## Success Criteria
- [x] Three failure demos recorded in note 03 with before/after responses.
- [x] Duplicate webhook: exactly one `webhook_events` row with `duplicate_count=1`, exactly one transition row, both responses 200.
- [x] Forged, tampered, mismatched or late webhooks never change payment status and never return 5xx.
- [x] Unit + IT green; Postman folder passes without waiting on the pay request.

## Risk Assessment
- Concurrent duplicates: both hit tx A; one wins the insert, the other gets no id back. Correct by construction; load-tested in increment 2.
- Webhook arriving while payment still CREATED (fast provider) -> `transition(CREATED -> SUCCEEDED)` is illegal -> stored REJECTED. Known gap; increment 2 adds the fix (lock + re-read). Document in note.
- Async sender hides send failures from the Postman user -> mock-provider logs every attempt at INFO with eventId and response status.

## Completion Notes (2026-11-07)
- Build green: gateway 52 unit + 15 integration tests (WebhookIngestionIT covers all 7 scenarios); mock-provider 4 tests. `./mvnw verify` SUCCESS.
- webhook_events accessed via JdbcClient (WebhookEventStore), not JPA, so the dedup INSERT can use Postgres ON CONFLICT DO NOTHING RETURNING. The table has no @Entity; ddl-auto=validate does not require one.
- PostgreSQL JDBC cannot infer a SQL type for a bare Instant: timestamps bound as OffsetDateTime(UTC); nullable payment_id bound as text with cast(:pid as uuid).
- Unknown provider code on the webhook path maps to 404 (WebhookUnknownProviderException), distinct from the 400 used when a merchant picks an unknown provider on create.
- HMAC signer lives in gateway common; the mock-provider deployable has its own inline HMAC in WebhookSender (separate process, cannot share the gateway class).
- Shared webhook secret: gateway fluxpay.providers.mock.webhook-secret == mock-provider mock.secret (dev default mock-webhook-secret-dev; compose sets both).
- Deviation: three failure demos delivered as WebhookIngestionIT assertions plus the note, not throwaway manual runs. Recorded in docs/learning/03-webhook-ipn.md.
