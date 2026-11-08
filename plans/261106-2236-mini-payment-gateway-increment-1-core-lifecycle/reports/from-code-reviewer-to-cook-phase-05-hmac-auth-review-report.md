# Phase 5 Merchant HMAC Auth — Code Review

Advisory review only. No code modified. Scope: servlet-filter HMAC-SHA256 request auth + AES-256-GCM secret-at-rest.

## Scope
- Files: MerchantAuthFilter, MerchantAuthFilterConfig, CanonicalRequest, CachedBodyHttpServletRequest, SecretCipher, SecretCipherStartupCheck, EncryptSecretTool, MerchantContext, Merchant, MerchantRepository, SecretDecryptException, HmacSigner, PaymentController, ProblemDetailWriter/Handler, application.yml, V5 migration, AbstractIntegrationTest + merchant/common tests.
- Focus: filter-reachable 500s, signature bypass, body re-read bugs, double registration, charset/HMAC drift.

## Overall Assessment
Solid, fail-closed design. Check order matches spec; constant-time compare, fresh IV, AAD binding, startup self-check, and FilterRegistrationBean-only registration are all correct. One concrete gap in the "no 500 reachable from the filter" guarantee (empty decrypted secret). Everything else is informational/low.

## Critical Issues
None.

## High Priority

### H1 — Empty decrypted secret reaches `HmacSigner` and throws a 500 (violates criterion 1)
`HmacSigner.hex` (HmacSigner.java:22-23) does `new SecretKeySpec(secret, "HmacSHA256")`. `SecretKeySpec` throws `IllegalArgumentException("Empty key")` when `secret.length == 0`. `hex`/`verify` only catch `GeneralSecurityException` (HmacSigner.java:30), so the `IllegalArgumentException` is unchecked and propagates out of `signer.verify` (MerchantAuthFilter.java:135) → `authenticate` → `doFilterInternal`, bypassing `reject(...)`. Result: HTTP 500, not a 401.

Reachability: a merchant row whose stored `v1:` envelope decrypts to an empty string (AES-GCM decrypt of empty plaintext yields a 0-length byte[] with a valid tag). `EncryptSecretTool` will happily encrypt an empty plaintext arg. The api key is public, so any caller who knows a misprovisioned merchant's api key and sends the three headers forces a 500. Not an auth bypass (verification never succeeds), but it is a filter-reachable 500 and the one hole in acceptance criterion 1.

The existing test `undecryptableStoredSecretIsBadSignatureNot500` (MerchantAuthFilterIT.java:114) covers decrypt *failure*, not decrypt-to-empty, so CI stays green while this path remains open.

Suggested fix (filter-level, keeps semantics consistent with the decrypt-fault handling): after `cipher.decrypt`, treat an empty secret like a key-management fault —
```java
if (secret.length == 0) {
    log.error("Auth rejected: stored secret for merchant {} decrypts to empty", apiKey);
    return "AUTH_BAD_SIGNATURE";
}
```
Alternatively harden `HmacSigner.verify` to return `false` on an empty/invalid key — but note `HmacSigner` is shared with the phase-4 webhook path, so the filter-level guard is the smaller, better-scoped change. Add a test seeding a merchant whose ciphertext decrypts to "".

## Medium Priority
None beyond H1.

## Low Priority / Informational

- **L1 — `AUTH_MISSING_HEADERS` for an unparseable timestamp** (MerchantAuthFilter.java:116). All three headers are present but `X-Timestamp` is non-numeric; returning the "missing headers" code is slightly misleading. No 500, no security impact. Distinct learning codes are intentional (R1 rejected); consider mapping this to `AUTH_STALE_TIMESTAMP` or a dedicated "bad timestamp" code for clarity. Non-blocking.

- **L2 — `request.getInputStream()` IOException is not a 401** (MerchantAuthFilter.java:75). A client disconnect mid-body throws a plain `IOException` (not `BodyTooLargeException`), which propagates and yields a container error, not a 401. This is inherent to servlet stream reads and outside the auth state machine; acceptable, noted for completeness.

- **L3 — `getReader()` charset from client Content-Type** (CachedBodyHttpServletRequest.java:66-67). `Charset.forName(encoding)` throws `UnsupportedCharsetException`/`IllegalCharsetNameException` on a bogus request charset. Spring MVC JSON binding uses `getInputStream()`, not `getReader()`, so this is not on the live path; if a future consumer uses the reader, wrap/fallback to UTF-8. Low.

- **L4 — Single-byte `read()` only** (CachedBodyHttpServletRequest.java:57-61). Bulk `read(byte[],off,len)` falls back to `InputStream`'s per-byte loop. Correct results; at the 64 KB cap the overhead is negligible. No action needed.

## Edge Cases Checked (no defect found)
- **Double registration**: filter is `new`-ed in `MerchantAuthFilterConfig` (not `@Component`) and mapped to `/api/*` only (MerchantAuthFilterConfig.java:34-39). No auto-registration against `/webhooks/**`. Correct.
- **Webhook/health exemption**: `/api/*` mapping does not match `/webhooks/**` or `/actuator/health`. Intact.
- **Body re-read**: filter reads raw bytes once, wraps in `CachedBodyHttpServletRequest`, controller re-reads buffered copy via `getInputStream()`. `isFinished/isReady` correct for sync reads. No stream-reuse bug. Body cap reads the *actual* stream (readCapped, line 145-158) so a lying `Content-Length` cannot bypass the 64 KB cap; cap is `total > maxBodyBytes` → exactly 65536 allowed.
- **Signature binding**: method+path+timestamp+idempotency-key+sha256hex(body) (CanonicalRequest.java:35-42). GET-signature-replayed-on-cancel rejected (method+path differ); body tamper rejected (hash differs). Verified by IT.
- **Constant-time compare**: `MessageDigest.isEqual` on hex bytes (HmacSigner.java:41). Secret bytes never logged (only api key / generic messages in filter and `SecretDecryptException`).
- **AES-GCM**: fresh 12-byte `SecureRandom` IV per encrypt (SecretCipher.java:65-66); api key bound as AAD (line 69, 100); decrypt wraps every `GeneralSecurityException`/`IllegalArgumentException` → `SecretDecryptException` → 401 (SecretCipher.java:104-106, filter 126-130), so `cipher.decrypt` itself cannot 500 the filter. Constructor fails fast on missing/non-base64/non-32-byte key (lines 44-60); `SecretCipherStartupCheck` fails boot if the dev key cannot decrypt the DEMO row.
- **Charset/HMAC drift vs CryptoJS**: canonical is UTF-8, all non-body fields ASCII, body enters only via lowercase-hex sha256; HMAC output lowercase hex. `HmacSignerTest` pins ASCII + UTF-8 (`xin chào`) vectors against openssl. Gateway signer path matches the test `hmac(...)` post-processor (both use `getRequestURI()`). Consistent with a CryptoJS signer provided it signs the raw `requestURI` and raw body bytes.
- **Merchant scoping**: controller reads only `ctx.merchantId()` from the verified `MerchantContext` and passes it to every orchestrator call (PaymentController.java:43,50,57). Cross-merchant get/cancel → `PaymentNotFoundException` → 404 `PAYMENT_NOT_FOUND` (ProblemDetailsHandler.java:33-36). `@RequestAttribute` is required=true, but the filter always sets the attribute on success, so the controller is only reached when it is present.

## Acceptance Criteria
1. Five 401 codes + GET-replay-on-cancel reachable: YES (MerchantAuthFilterIT). No-500: MOSTLY — see **H1** (empty decrypted secret).
2. Signature binds method+path+ts+idem+body hash: YES.
3. Constant-time compare + secret never logged: YES.
4. AES-GCM fresh IV / AAD / decrypt-failure 401 / fail-fast on bad key and undecryptable DEMO: YES.
5. Payments scoped to authenticated merchant, cross-merchant → 404: YES.
6. No regression; webhook exemption intact: YES (registration scoped to `/api/*`). Did not run `./mvnw verify` in this review (reported green: 62 unit + 24 IT); H1 is not covered by a test so green is expected.

## Metrics
- Filter-reachable 500 paths: 1 (H1), gated on operator provisioning an empty secret.
- Signature-bypass paths: 0 found.
- Double-registration: none.
- Dev key in `.env.example` / `application-test.yml` / V5 ciphertext: present and intentional (red-team finding 8, documented) — not re-litigated.

## Unresolved Questions
1. Should an empty decrypted secret be rejected as `AUTH_BAD_SIGNATURE` (recommended) or prevented at provisioning time (e.g. `EncryptSecretTool` / a NOT-NULL+length check)? H1 only needs one, but a provisioning guard would also stop empties being stored.
2. Does the real CryptoJS Postman signer sign `requestURI` exactly as the gateway sees it (raw, URL-encoded, no query, no context path)? The code is internally consistent; cross-tool parity depends on the Postman script, which was not in scope here.

Status: DONE_WITH_CONCERNS
Summary: Design is fail-closed and correct on signature binding, constant-time compare, AES-GCM, registration scope, and merchant scoping. One concrete filter-reachable 500 (empty decrypted secret → SecretKeySpec IllegalArgumentException in HmacSigner, uncaught) is the lone gap in the "no 500" guarantee.
Concerns: H1 should be closed before this is called complete against acceptance criterion 1; add a test seeding a merchant whose ciphertext decrypts to "".
