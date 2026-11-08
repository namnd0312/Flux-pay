# Red-Team Plan Review: Security Adversary + Fact Checker

- Date: 2026-11-06
- Reviewer role: code-reviewer (security adversary, fact checker)
- Scope: plan.md + phase-01..06 of `plans/261106-2236-mini-payment-gateway-increment-1-core-lifecycle/`
- Codebase: empty; evidence = plan file:line and sibling `english-study/backend/pom.xml`
- Not re-litigated: no Spring Security, learning-first, no refunds, X-Merchant-Id trusted before phase 5 (deliberate failure demo)

Paths below are relative to `plans/261106-2236-mini-payment-gateway-increment-1-core-lifecycle/`.

## Fact-check results (claims verified)

| Claim | Location | Verdict |
|---|---|---|
| Boot 4 starter names `spring-boot-starter-webmvc/flyway/data-jpa/validation/actuator`, `*-webmvc-test`, `*-data-jpa-test`, `spring-boot-testcontainers`, `testcontainers-postgresql`, `testcontainers-junit-jupiter`, `flyway-database-postgresql` | plan.md:31, phase-01:43 | Correct: all present in `/Users/admin/Desktop/DEV/AI/english-study/backend/pom.xml` lines 35-134 (parent 4.1.2, line 8) |
| `MessageDigest.isEqual` constant-time | phase-04:56 | Correct (since JDK 6u17) |
| `ContentCachingRequestWrapper` only caches after read | phase-05:61 | Correct |
| HMAC needs raw secret so encrypt not hash | brainstorm:85 | Correct |
| `@RestClientTest` artifact in Boot 4 | phase-03:65 | Unverifiable from sibling pom (no `spring-boot-restclient-test` there); plan already flags it |
| "timestamp window blocks replays" | phase-05:13 | **Wrong** - see Finding 2 |
| Flyway `BaseJavaMigration` can use `SecretCipher` | phase-05:35,38 | Partially wrong - see Finding 9 |

## Finding 1: Signed payload omits HTTP method, path and query; empty-body requests share one signature
- **Severity:** High
- **Location:** Phase 5, "Requirements" - phase-05-merchant-hmac-auth.md:17
- **Flaw:** `X-Signature = hex(HMAC-SHA256(secret, timestamp + "." + rawBody))` with `rawBody = ""` for GET. Nothing binds the signature to method, path or query string. Every body-less request (`GET /api/v1/payments/{id}`, `POST /api/v1/payments/{id}/cancel`) signs the identical payload `"<ts>."`.
- **Failure scenario:** Attacker on-path (or any log/proxy that records headers) captures one merchant `GET /api/v1/payments/abc` with its `X-Api-Key`/`X-Timestamp`/`X-Signature`. Within 5 minutes they replay those three headers on `POST /api/v1/payments/<any-id-of-that-merchant>/cancel` and on `GET` of every other payment id. The filter validates it: same secret, same timestamp, same empty body. A read-only signature becomes a write capability.
- **Evidence:** phase-05:17 "`X-Signature` = `hex(HMAC-SHA256(secret, timestamp + "." + rawBody))` (rawBody = "" for GET)". No mention of method/path anywhere in phase 5. Cancel endpoint has no body: phase-02:19.
- **Suggested fix:** Sign a canonical string `method + "\n" + path + "\n" + query + "\n" + timestamp + "\n" + sha256hex(body)` (what VNPAY/MoMo effectively do by signing all params). Add `MerchantAuthFilterTest` case: signature from GET replayed onto cancel -> 401.

## Finding 2: "Timestamp window blocks replays" is false; no nonce and `Idempotency-Key` is outside the signature
- **Severity:** High
- **Location:** Phase 5, "Overview"/"Requirements" - phase-05:13,17; Phase 6 "Requirements" - phase-06:17,28
- **Flaw:** A 5-minute window bounds replay, it does not block it. The plan has no nonce store and the only per-request unique value (`Idempotency-Key`, phase-06:17) is a header, not part of `rawBody`, so it is unsigned (phase-05:17).
- **Failure scenario:** (a) Phases 5 only: capture a signed `POST /api/v1/payments`, re-send it 10x within 5 min -> 10 payments, 10 provider sessions. (b) After phase 6: re-send the same signed body with a *different* `Idempotency-Key` -> idempotency layer sees a NEW request; only the V7 `(merchant_id, order_id)` constraint saves it with a 409. The note (phase-05:44 "why the timestamp is inside the signed payload (replay window)") will teach the learner that timestamp = replay protection, which is the wrong mental model.
- **Evidence:** phase-05:13 "a timestamp window blocks replays"; phase-05:17 signature covers only `timestamp + "." + rawBody`; phase-06:28 key is a header with `requestHash (SHA-256 of rawBody)`.
- **Suggested fix:** Include `Idempotency-Key` (or an explicit `X-Nonce`) in the canonical signed string and, for phase 6, enforce `(merchant_id, idem_key)` uniqueness as the replay guard. Reword note 04: "window limits the replay lifetime; nonce/idempotency key eliminates it".

## Finding 3: AES-GCM spec has no IV/nonce, AAD or storage-format requirement
- **Severity:** High
- **Location:** Phase 5, "Requirements"/"Architecture" - phase-05:21,28
- **Flaw:** "Secrets encrypted with AES-GCM using key from env `FLUXPAY_SECRET_KEY` (base64, 32 bytes)" says nothing about a 12-byte random IV per encryption, prepending it to the ciphertext, or AAD binding to the merchant id. AES-GCM with a static key and a repeated/zero IV leaks XOR of plaintexts and allows forging the GCM tag (authentication key recovery).
- **Failure scenario:** Implementer (or the learner in the "Tự phá thử" exercise) uses `new GCMParameterSpec(128, new byte[12])` or a fixed IV. Two merchants' secrets encrypted under the same key+IV: anyone with DB read and one known secret (the committed DEMO one, Finding 9) XORs ciphertexts and recovers every other merchant secret -> forges signed requests for all merchants.
- **Evidence:** phase-05:21 and :28 mention only algorithm and key; `SecretCipherTest` (phase-05:40) has no listed case for "two encryptions of the same plaintext differ".
- **Suggested fix:** Specify: `SecureRandom` 12-byte IV per call, output `base64(iv || ciphertext||tag)`, AAD = merchant `code`, key-version prefix (`v1:`) for future rotation. Add test: encrypt twice -> different ciphertexts; decrypt with wrong AAD -> fails.

## Finding 4: Merchant scoping specified/tested only for GET; cancel is an IDOR; payment id type unspecified
- **Severity:** High
- **Location:** Phase 5, "Requirements"/"Test" - phase-05:19,40; Phase 2 "Architecture" - phase-02:19,28
- **Flaw:** phase-05:19 defines scoping as "GET on another merchant's payment -> 404" and `PaymentScopingIT` (phase-05:40) only asserts "merchant A cannot read merchant B payment". `POST /api/v1/payments/{id}/cancel` (phase-02:19) is never listed as scoped or tested. `Payment.id` (phase-02:28) has no type; `merchants.id` is uuid (phase-01:33) but payments is not stated.
- **Failure scenario:** Merchant B (valid signed request, own key) calls `POST /api/v1/payments/<A's id>/cancel`. If the orchestrator loads by id only, A's PENDING payment becomes CANCELLED with `source=MERCHANT`; the later SUCCESS webhook is REJECTED (phase-04:19) and the customer's money is in limbo. If `payments.id` ends up `bigserial`, B enumerates all of A's ids and cancels them in a loop.
- **Evidence:** phase-05:19 (GET only), phase-05:40 (read-only test), phase-02:19 (cancel endpoint), phase-02:28 (`id` untyped).
- **Suggested fix:** State the invariant: every `payment` repository call on `/api/**` is `findByIdAndMerchantId`. Specify `payments.id uuid`. Add `PaymentScopingIT` case: cross-merchant cancel -> 404 and status unchanged.

## Finding 5: 401 code set + check ordering is an API-key enumeration and merchant-state oracle
- **Severity:** Medium
- **Location:** Phase 5, "Requirements"/"Architecture" - phase-05:18,33
- **Flaw:** Distinct public codes `AUTH_UNKNOWN_MERCHANT`, `AUTH_MERCHANT_INACTIVE`, `AUTH_STALE_TIMESTAMP`, `AUTH_BAD_SIGNATURE` plus order "headers -> merchant by api key -> active -> timestamp -> signature" means the server answers "is this API key real / active?" to anyone, before any proof of secret possession.
- **Failure scenario:** Unauthenticated attacker brute-forces `X-Api-Key` with garbage signature; `AUTH_UNKNOWN_MERCHANT` vs `AUTH_BAD_SIGNATURE` tells them exactly when they hit a valid key, and `AUTH_MERCHANT_INACTIVE` reveals suspended merchants. Response-time also differs because HMAC is only computed on the valid-key path.
- **Evidence:** phase-05:18 code list; phase-05:33 "Order of checks in filter: headers present -> merchant by api key -> active -> timestamp window -> signature."
- **Suggested fix:** Keep the fine-grained reason in the WARN log only; return a single external code (`AUTH_UNAUTHORIZED`) for unknown/inactive/bad-signature, keep `AUTH_MISSING_HEADERS` and `AUTH_STALE_TIMESTAMP` if desired. Compute HMAC against a dummy secret on the unknown-key path. Teach this as the "don't help the attacker" lesson in note 04.

## Finding 6: Unbounded eager body buffering before authentication (filter and webhook) - memory DoS
- **Severity:** Medium
- **Location:** Phase 5, "Architecture"/"Risk" - phase-05:29,61; Phase 4, "Architecture"/"Risk" - phase-04:25,71
- **Flaw:** `CachedBodyHttpServletRequest` "reads bytes eagerly" and the webhook controller uses `@RequestBody String rawBody`. Both read the entire body into heap *before* signature verification (signature cannot be checked without the body). No `Content-Length` cap is specified. Tomcat's `maxPostSize` applies only to form parsing, not raw JSON streams.
- **Failure scenario:** Unauthenticated client opens 20 connections and streams 500 MB bodies to `/webhooks/MOCK` or `/api/v1/payments`. Each request allocates a byte[]/String of that size before being rejected with 401 -> OOM on a default JVM heap in Compose.
- **Evidence:** phase-05:61 "use a custom wrapper that reads bytes eagerly. Note the cost for large bodies (not a concern here)"; phase-04:71 "`@RequestBody String rawBody`".
- **Suggested fix:** In both paths reject `Content-Length > 64 KB` (or missing/chunked above a read cap) with 413 before buffering; set `server.max-http-request-header-size` and a `spring.servlet` / Tomcat `maxSwallowSize`. One line in each phase's requirements is enough.

## Finding 7: Provider exception message persisted as transition reason and returned to merchants (internal disclosure)
- **Severity:** Medium
- **Location:** Phase 3, step 7 - phase-03:53; Phase 2 "Requirements" - phase-02:18; Phase 6 "Architecture" - phase-06:35
- **Flaw:** "FAILED on `ProviderException`, 502 response, transition reason = exception message". `GET /api/v1/payments/{id}` returns "payment + list of transitions" (phase-02:18). Phase 6 stores the 502 body for replay (phase-06:35). RestClient exceptions embed the target URL (`http://mock-provider:8081/mock/v1/payments`), host resolution errors, and sometimes response snippets.
- **Failure scenario:** Merchant (or anyone who later reads the audit table) sees internal service hostnames/ports and provider error bodies in the public API. When a real provider is wired, provider-side error text (which can include provider request ids, partner codes, or even echoed credentials in some gateways' error pages) is persisted verbatim and replayed.
- **Evidence:** phase-03:53 "transition reason = exception message"; phase-02:18 GET exposes transitions.
- **Suggested fix:** Store a stable reason code (`PROVIDER_UNAVAILABLE`, `PROVIDER_TIMEOUT`) in `payment_transitions.reason`; log the exception with the payment id; never return raw exception text in RFC 7807 `detail`.

## Finding 8: Webhook payment lookup keyed on `providerRef` only, not `(providerCode, providerRef)`; merchantRef and currency not cross-checked
- **Severity:** Medium
- **Location:** Phase 4, "Processing order" step 2 and "Requirements" - phase-04:37,18
- **Flaw:** "parse -> find payment by providerRef". Payment stores `providerCode` (phase-02:28) but the lookup ignores it. The amount check (phase-04:18) is the only cross-check; `merchantRef` (the gateway's own payment id echoed back) and `currency` are not compared.
- **Failure scenario:** Increment 1 has one provider, but the interface is designed for N (phase-03:13,20). A second provider adapter signed with its own secret delivers an event whose `providerRef` collides with a MOCK ref (refs are provider-assigned opaque strings; collisions are not controlled by the gateway). The webhook is validly signed by provider B, dedup key `(provider_code=B, event_id)` is new, amount matches, and payment created on provider A flips to SUCCEEDED without A ever being paid. Also: a provider-side bug that swaps refs between two sessions of equal amount is undetectable because `merchantRef` is ignored.
- **Evidence:** phase-04:37 "find payment by providerRef (404 -> ...)"; phase-04:18 verifies only "amount matches payment".
- **Suggested fix:** `findByProviderCodeAndProviderRef(code, ref)`; additionally assert `event.merchantRef == payment.id` and `currency == VND`; mismatch -> REJECTED `REF_MISMATCH`. Cheap now, and the explanation fits note 03's "verify everything the provider echoes back" lesson.

## Finding 9: Secret hygiene: dev encryption key committed, plaintext DEMO secret in git history and Postman env, no fail-closed for missing key; Java migration DI claim partially wrong
- **Severity:** Medium
- **Location:** Phase 5, "Risk" - phase-05:63; "Related Code Files" - phase-05:35,38; Phase 1 - phase-01:17,33,49
- **Flaw:**
  1. phase-05:63 "dev key in `.env.example`" commits a working AES key; phase-01:17 says "no secrets committed". Any default key that works out of the box will be the production key for someone who copies the project.
  2. V2 seeds DEMO with a plaintext secret (phase-01:33); V5 "replaces" it in the DB, but V2 stays in git forever and `flux-pay.postman_environment.json` carries `merchantSecret` (phase-01:49). Success criterion phase-05:57 "No plaintext secret in DB after V5" is true but misleading.
  3. No statement that `SecretCipher` must fail at startup when `FLUXPAY_SECRET_KEY` is absent. The obvious shortcut for green Testcontainers tests is a hard-coded fallback key -> permanent backdoor.
  4. Fact check: a classpath-scanned Flyway `BaseJavaMigration` (phase-05:38 `db/migration/V5__EncryptMerchantSecrets.java`) is instantiated by Flyway, not Spring, so it cannot inject `SecretCipher`. It only works if V5 is registered as a Spring `JavaMigration` bean (Spring Boot auto-detects those) and `SecretCipher` is constructible before Flyway runs. Plan does not say which.
- **Failure scenario:** Learner runs `docker compose up` with `.env.example` copied unchanged; the DB dump plus the public repo gives any reader the AES key and therefore every merchant secret. Separately, tests pass with a fallback key and nobody notices the env var was never wired in compose.
- **Evidence:** phase-05:63; phase-01:33 "secret stored plaintext in V2 for now"; phase-01:49 env var `merchantSecret`; phase-05:38 migration path under `db/migration`.
- **Suggested fix:** `.env.example` contains `FLUXPAY_SECRET_KEY=<run: openssl rand -base64 32>` not a value; `SecretCipher` throws on missing/invalid key length; tests inject a random key via `@DynamicPropertySource`. Rotate the DEMO secret in V5 (generate a new one, print it once in the migration log / README step) so the V2 value in history is dead. State explicitly that V5 is a Spring-managed `JavaMigration` bean (package outside `db/migration` so Flyway's scanner does not also instantiate it twice).

## Finding 10: HMAC input defined as Java `String`, charset and hex-case unspecified; Vietnamese bodies will mismatch between Postman and filter
- **Severity:** Medium
- **Location:** Phase 5, "Architecture"/"Requirements"/step 7 - phase-05:31,17,49
- **Flaw:** `HmacSigner.sign(secret, payload) hex` takes a String payload, and the filter concatenates `timestamp + "." + rawBody` as a String. Postman's CryptoJS signs the UTF-8 request body. If the filter decodes bytes with the platform default charset (or `request.getReader()` with `ISO-8859-1` when no charset is declared, the servlet default), the `description` field with Vietnamese text (the project's note language, plan.md:27) produces different bytes -> `AUTH_BAD_SIGNATURE` on every real-world request, and the learner's likely "fix" is to loosen the check. Hex case (`a1b2` vs `A1B2`) is also unspecified; `openssl dgst` (phase-06:40) emits lower-case, CryptoJS `toString()` emits lower-case, Java `HexFormat` default is lower-case, but a `String.equals` after `toUpperCase` on one side only is a classic silent mismatch.
- **Failure scenario:** `scripts/burst-create.sh` and the Postman collection pass with ASCII `"description":"test"`, `PaymentScopingIT` passes, then any body with "Thanh toán đơn hàng" is rejected; or worse, the implementer normalises the body (trim / re-serialise JSON) before signing, breaking the "exact bytes" property that phase-04:25 correctly insists on for webhooks.
- **Evidence:** phase-05:31 `sign(secret, payload)` (String); phase-05:17 `timestamp + "." + rawBody`; phase-05:49 "Postman pre-request script (CryptoJS HMAC)"; phase-06:40 `openssl dgst -sha256 -hmac`.
- **Suggested fix:** Define `HmacSigner.sign(byte[] secret, byte[] payload)`; filter builds `ts.getBytes(UTF_8) + "." + rawBodyBytes` without any String round-trip; compare lower-case hex via `MessageDigest.isEqual` on decoded bytes. Add a `HmacSignerTest` vector with a non-ASCII body and a known expected digest computed by `openssl`.

## Unresolved questions
- Is the merchant-facing signature scheme meant to mirror VNPAY (`vnp_SecureHash` over sorted query params) for teaching fidelity, or a generic header scheme? Findings 1-2 and 10 assume the generic scheme as written; a VNPAY-faithful scheme would need its own canonicalisation spec.
- Is a committed working dev key (Finding 9) an accepted learning-project trade-off? If yes, the plan should say so and the README must say "never reuse".
