---
phase: 5
title: "Merchant HMAC Auth"
status: done
priority: P1
dependencies: [4]
effort: "1 day"
---

# Phase 5: Merchant HMAC Auth

## Overview
Turn the unverified `X-Merchant-Id` lookup into VNPAY/MoMo-style request signing: the API key identifies the merchant, an HMAC-SHA256 signature over a canonical string (method, path, timestamp, idempotency key, body hash) proves possession of the secret, and a timestamp window bounds replay lifetime. Merchant secrets are stored encrypted with a simple AES-GCM scheme. Implemented as a servlet filter, no Spring Security.

Red-team changes applied: #6 (canonical string binds method/path/key; byte-based signing; replay wording), #7 (cancel scoped and tested), #8 accepted as modified (simplified encryption: SQL migration with precomputed ciphertext, random IV, test key, fail-fast), #9 (filter writes problem details, decrypt failure is 401 not 500), #10 (header stays `X-Merchant-Id`), #14 (body cap).

## Requirements
- Functional:
  - Headers on every `/api/**` request: `X-Merchant-Id` (api key, unchanged since phase 2), `X-Timestamp` (epoch millis), `X-Signature`.
  - Canonical string, UTF-8 bytes, newline separated:
    ```
    METHOD            e.g. POST
    PATH              e.g. /api/v1/payments   (no query, no host)
    X-Timestamp
    Idempotency-Key   header value, or empty line if absent
    sha256hex(body)   lowercase hex of raw body bytes; sha256 of empty input for GET
    ```
    `X-Signature = lowercasehex(HMAC-SHA256(secretBytes, canonicalBytes))`.
  - Reject with 401 and codes: `AUTH_MISSING_HEADERS`, `AUTH_UNKNOWN_MERCHANT`, `AUTH_MERCHANT_INACTIVE`, `AUTH_STALE_TIMESTAMP` (|now - ts| > 5 min), `AUTH_BAD_SIGNATURE` (also used when the stored secret cannot be decrypted; cause logged at ERROR).
  - Authenticated merchant placed in `MerchantContext` (request attribute) and used by `PaymentOrchestrator`; `MerchantResolver` from phase 2 is deleted.
  - `/webhooks/**` and `/actuator/health` excluded (webhooks use provider signatures from phase 4).
  - Body cap 64 KB on `/api/**` (413) before buffering.
  - Secrets at rest: `merchants.secret` holds `v1:` + base64(iv || ciphertext || tag), AES-256-GCM, fresh 12-byte `SecureRandom` IV per encryption, AAD = merchant api key. Key from env `FLUXPAY_SECRET_KEY` (base64, 32 bytes). App fails to start if the key is missing/invalid or if the DEMO secret does not decrypt (startup self-check).
- Non-functional: constant-time comparison; filter reads body once (eager byte buffer) and re-exposes it; Postman pre-request script computes the signature so the collection stays usable; all responses from the filter are RFC 7807 via `ProblemDetailWriter`.

## Architecture
```
merchant/
  Merchant (entity), MerchantRepository (findByApiKey)
  SecretCipher                 AES-GCM encrypt/decrypt as above; fail-fast constructor; wraps GeneralSecurityException -> SecretDecryptException
  SecretCipherStartupCheck     ApplicationRunner: decrypt DEMO secret, abort startup with a clear message on failure
  EncryptSecretTool            class with main(): prints ciphertext for (key, apiKey, plaintext); run via
                               ./mvnw -q -pl gateway spring-boot:run -Dspring-boot.run.mainClass=com.fluxpay.gateway.merchant.EncryptSecretTool -Dspring-boot.run.arguments="<apiKey> <plaintext>"
  MerchantAuthFilter           OncePerRequestFilter on /api/**: cap + wrap request (CachedBodyHttpServletRequest), validate headers, verify, set MerchantContext; on failure ProblemDetailWriter.write(401, code)
  MerchantContext              request attribute holder
common/HmacSigner              (from phase 4) + CanonicalRequest.build(method, path, ts, idemKey, bodyBytes)
```
Order of checks in filter: body cap -> headers present -> merchant by api key -> active -> timestamp window -> decrypt secret -> signature. Fail closed. Log reason at WARN without the signature value. Distinct 401 codes are kept on purpose for learning (note explains that production collapses them to avoid key enumeration).

Flyway `V5__encrypt_demo_merchant_secret.sql`: plain SQL `UPDATE merchants SET secret = 'v1:...' WHERE api_key = 'demo-merchant'`, ciphertext precomputed once with `EncryptSecretTool` using the committed **dev key**. No Java migration.

Key handling: `.env.example` and `src/test/resources/application-test.yml` carry the same dev key (`FLUXPAY_SECRET_KEY`), clearly labelled "dev only, never reuse". README: to rotate, generate a key with `openssl rand -base64 32`, re-encrypt with `EncryptSecretTool`, and apply a new migration.

## Related Code Files
- Create: `gateway/.../merchant/**` (except `MerchantResolver`), `common/CanonicalRequest`, `common/CachedBodyHttpServletRequest`, `db/migration/V5__encrypt_demo_merchant_secret.sql`, `.env.example`, `docs/learning/04-hmac-auth.md`, Postman pre-request script + folder "05 Auth".
- Modify: `payment/PaymentOrchestrator` + controller (merchant from `MerchantContext`), `application.yml` (`fluxpay.auth.timestamp-tolerance: 5m`, `fluxpay.auth.max-body-bytes: 65536`), `application-test.yml` (dev key), `docker-compose.yml` (`FLUXPAY_SECRET_KEY` from `.env`), `README.md` (key generation + rotation, dev-key warning), `AbstractIntegrationTest` (signed request helper `signedPost/signedGet`).
- Delete: `merchant/MerchantResolver`.
- Test: `CanonicalRequestTest` + `HmacSignerTest` additions (Vietnamese body vector), `SecretCipherTest` (roundtrip; two encryptions of the same plaintext differ; wrong AAD fails; wrong key fails; missing key fails construction), `MerchantAuthFilterTest` (MockMvc: valid passes and body still readable by controller; each 401 code; GET signature replayed on cancel -> 401; oversize -> 413), `PaymentScopingIT` (merchant B cannot GET **or cancel** merchant A's payment -> 404; second merchant inserted by the test with a secret encrypted via `SecretCipher`).

## Implementation Steps
1. **Failure demo first:** with the phase 4 code, from Postman send `X-Merchant-Id: demo-merchant` and create a payment. Then insert a second merchant via SQL and use its api key to cancel the DEMO merchant's payment: it works. Anyone who knows an api key can act as that merchant. Second demo: capture a request and resend it a day later -> accepted.
2. Write `docs/learning/04-hmac-auth.md` (VN): shared secret vs password, what HMAC is, why method + path + body must be signed (tamper and cross-endpoint replay), why the timestamp is inside the signed payload and what the window does (**bounds** replay lifetime; a signed `Idempotency-Key` removes duplicate effects, see phase 6), how VNPAY `vnp_SecureHash` and MoMo `signature` do the same thing, why secrets are encrypted at rest with a random IV, why this is still not production grade (KMS/HSM, key rotation), why production collapses the 401 codes. Two demos above.
3. `CanonicalRequest`; extend `HmacSigner` tests with a non-ASCII vector.
4. `SecretCipher` + `EncryptSecretTool` + startup check + tests; generate the DEMO ciphertext; V5; `.env.example`; test key; compose; README.
5. `CachedBodyHttpServletRequest`, `MerchantAuthFilter`, `MerchantContext`; register filter for `/api/*` only; delete `MerchantResolver`.
6. Scope payments to `MerchantContext`.
7. Postman pre-request script (CryptoJS HMAC + SHA-256 over the canonical string) using env `merchantId`, `merchantSecret`.
8. Tests listed above.
9. Postman: valid create; missing headers; wrong secret; timestamp 10 min old; GET signature reused on cancel; cross-merchant GET and cancel -> 404.
10. Exercise ("Tự phá thử"): modify one character of the body after signing and send; then remove PATH from the canonical string in a scratch branch and show the GET-on-cancel replay passes again.

## Success Criteria
- [ ] Both failure demos recorded in note 04.
- [ ] All five 401 codes plus the GET-signature-on-cancel case covered by `MerchantAuthFilterTest`; no 500 reachable from the filter.
- [ ] `./mvnw verify` green on a clean checkout with no env vars set (test key comes from `application-test.yml`).
- [ ] App refuses to start without `FLUXPAY_SECRET_KEY` or with a key that cannot decrypt DEMO.
- [ ] Postman collection works end to end with the pre-request script, including a Vietnamese description.

## Risk Assessment
- Clock skew between merchant and gateway -> 5 min tolerance; mention NTP in note.
- Dev key is committed by design -> labelled in three places; README says rotation requires a new migration. Acceptable for a learning repo.
- CryptoJS and Java must produce identical bytes -> the canonical string is ASCII except the body hash, which removes the charset problem from the HMAC input.

## As-built notes (deviations)
- Empty decrypted secret: a merchant whose ciphertext decrypts to 0 bytes would make the HMAC signer throw -> the filter now returns 401 AUTH_BAD_SIGNATURE (ERROR log) before signing, so no 500 is reachable. Found by the phase-5 review; covered by MerchantAuthFilterIT.emptyDecryptedSecretIsBadSignatureNot500.
- Present-but-unparseable X-Timestamp maps to AUTH_STALE_TIMESTAMP (a timestamp-validity failure) rather than AUTH_MISSING_HEADERS.
- PaymentScopingIT folded into PaymentLifecycleIT.anotherMerchantCannotSeeOrCancel (cross-merchant GET and cancel, signed as a second merchant whose encrypted secret the test seeds) rather than a separate file.
- Filter registered via FilterRegistrationBean (order 10) on /api/*, not @Component, to avoid double registration and to leave room before the idempotency filter.
- Review: reports/from-code-reviewer-to-cook-phase-05-hmac-auth-review-report.md (1 High fixed, rest clean).
