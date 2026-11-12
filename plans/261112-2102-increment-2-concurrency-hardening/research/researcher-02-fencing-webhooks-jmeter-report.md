# Research 02: lease fencing, early webhooks, fake-provider retries, JMeter

Scope: 5 web lookups used. Code read: IdempotencyStore/Service/Filter, V6 migration, WebhookIngestionService, PaymentOrchestrator, mock WebhookSender/MockProperties, docs/learning/05, burst-replay.sh.
Verified = fetched this session. UNVERIFIED = from memory, check before citing in docs.

## 1. Lease fencing

**Canonical source (verified)**: Kleppmann, "How to do distributed locking" https://martin.kleppmann.com/2016/02/08/how-to-do-distributed-locking.html
- Expiry alone is unsafe: holder pauses (GC), lease expires, B takes lock and writes, A wakes and writes stale. Checking expiry just before write does not fix it (pause between check and write).
- Fix: monotonically increasing token per acquisition, sent with every write. Storage rejects writes with token lower than highest seen.

**Brandur/Stripe design (verified)**: https://brandur.org/idempotency-keys
- `locked_at` set while working; acquire only if unlocked or expired; contention -> 409.
- Error path clears `locked_at` at once so retry proceeds.
- Each phase commits `recovery_point`; retry after expiry resumes from it.
- Response stored with `response_code/body`, `recovery_point = finished`, lock cleared.
- Upsert runs SERIALIZABLE. Our code uses conditional UPDATE instead, which is equivalent for one row.

**Our gap**: `complete()`/`abandon()` match only `(merchant_id, idempotency_key)`. Zombie A can overwrite B's row or delete it.

**Design options for single table**

| Option | Column | Claim | Take-over | Guarded write |
|---|---|---|---|---|
| A. owner token | `lease_token uuid` | set random UUID | set new UUID | `... and lease_token = :mine` |
| B. epoch (canonical fencing) | `lease_epoch bigint` | insert with 1 | `set lease_epoch = lease_epoch + 1 ... returning lease_epoch` | `... and lease_epoch = :mine` |

- Correctness here is the same. The DB row is both lock and storage, so the conditional WHERE is the storage-side check Kleppmann requires.
- A is fewer moving parts (no counter). B gives monotonic ordering and matches the term the course teaches; it also helps if a second store is ever fenced.
- Ranking (learning project, KISS): 1) epoch B, since it is the canonical term and costs one column and one `+1`; 2) UUID A, equally correct, pick if you prefer no counter.
- Carry the token in `begin()` outcome (`ofNew(token)`) into the filter, which passes it to `complete/abandon`.
- Do NOT reuse `lease_until` as the check. Time alone is the flaw being fixed.

**Zombie response on 0 rows (recommendation, not a standard)**
- Log WARN with merchant, key, my epoch, and the current row's epoch (one extra SELECT, only on this rare path).
- Do not store, do not delete.
- Client: return the response the zombie computed. The payment side is already protected by `ux_payments_live_order` and the create-once invariant, so this response describes real committed work. Trade-off: replay may later show B's status. Alternative: return 409 `IDEMPOTENCY_IN_PROGRESS` (safer for the merchant, but hides a real result). Decision for user (see Q1).

**Stripe-style rules (partly UNVERIFIED, from memory of Stripe docs)**
- Results saved only if the endpoint started executing; validation failures and concurrent-conflict 409s are not saved.
- Concurrent request with same key returns 409 `idempotency_error`.
- Our code matches this shape. Our 502 is stored (deterministic per our rules), Stripe also stores 5xx-after-execution in some cases (UNVERIFIED).

**Pitfalls (learner)**
- Epoch compared with `>=` instead of `=`. Use equality on the owner's own token.
- Forgetting to guard `abandon()` too (it deletes the new owner's row).
- Returning the epoch from `find()` and trusting it. Only the claim/take-over return value is the owner's proof.
- Testing takeover with `Thread.sleep` past lease instead of setting lease in the past via SQL. Use SQL: `update ... set lease_until = now() - interval '1 s'`.
- Lease shorter than worst-case provider timeout: zombie is then normal, not rare. Check provider HTTP timeout against the 60s lease.

## 2. Early / out-of-order webhooks

**Verified (Stripe, https://docs.stripe.com/webhooks)**
- Respond 2xx fast; defer work to a queue.
- Retries: up to 3 days, exponential backoff (live mode). Sandbox: ~3 retries over hours.
- Non-2xx, 3xx and timeouts count as failure. Redirects are failures.
- Each retry gets a NEW timestamp and signature.
- No ordering guarantee. Do not use `created` to order. Dedupe by event ID.
- If an object is missing when an event arrives, fetch it from the API.

**Adyen / PayPal / VNPAY / MoMo**: Adyen docs fetch returned 404 (not verified). Memory, UNVERIFIED: Adyen retries non-2xx for about 24h with backoff; PayPal webhooks retry for about 3 days; VNPAY and MoMo IPN retry schedules are merchant-facing and vary, with merchants expected to dedupe by txn ref and be idempotent. Verify against provider docs before writing into `docs/`.

**Our bug, precisely**
- Step A inserts the event row (dedupe key). Step B validates. Step C marks REJECTED and the process returns 200.
- Early webhook: `UNKNOWN_PAYMENT` (provider_ref not yet stored), or `ILLEGAL_TRANSITION` when payment is still CREATED. Provider sees 200, never retries. Retry is later swallowed as duplicate. Success lost.
- Anti-pattern: a retryable rejection must not consume the dedup key and must not return 200.

**Remedies compared (synchronous, pre-Kafka)**

| Option | What | Pros | Cons |
|---|---|---|---|
| a. non-2xx for retryable, no dedup row | roll back tx A; return 503 for `UNKNOWN_PAYMENT`/CREATED | Smallest diff. Uses provider retry, which is what real providers do | Depends on provider retrying. Mock does not. Needs bounded attempts at provider side |
| b. inbox + sweeper | persist raw event as PENDING, re-process later | Survives provider giving up. Robust | More code now. Duplicates the planned Kafka inbox. Defer |
| c. lookup by merchant ref | `merchantRef` = our payment id (already in code), load row | Finds payment before provider_ref is stored | Lock wait does NOT help: tx2 has not started (provider HTTP call holds no tx), so there is no row lock to wait on. Still must answer retryable |
| d. store provider_ref before the call | persist ref at tx1 | Removes race entirely | Only possible if provider accepts our id as its reference. We already pass `id` into `ProviderPaymentRequest`; check the provider contract. If provider assigns its own ref, impossible |

**Recommended minimal fix now**: (a) + (c) together.
- Lookup by merchantRef (our payment id). If row missing -> 404 or retryable 503 (decide). If row is CREATED (tx2 pending) -> 503 with no dedup row. If terminal or PENDING -> proceed, dedupe as now.
- Use (d) only if the provider echoes our id.
- Defer (b) to the Kafka increment.

**Hidden trap if transitioning CREATED -> SUCCEEDED early**: tx2 later calls `transition(PENDING)` on a SUCCEEDED row and throws. Create flow then returns 500 with a success at the provider. Either wait for PENDING (option a) or make tx2 tolerant of a later state. Check `PaymentStateMachine` transitions (not read).

**Pitfalls**
- Marking dedupe row then returning 503: provider retry is swallowed. Insert dedup only after deciding the event is final.
- Rolling back tx A but keeping a `@Transactional` outer method that swallows exceptions.
- Using `created` timestamp for ordering.
- Retrying with a new `eventId` in mock: dedupe is not tested. Retries must reuse eventId and body.

## 3. Fake provider: retries and out-of-order

Current mock: one async send per pay; `delayMs`, `duplicates` (same eventId, sent N extra times immediately). No retry on non-2xx; no ordering knob; no create-time behaviour.

Proposed knobs (YAGNI: add only these):
- `callback.retry.maxAttempts`, `initialBackoffMs`, `multiplier`: retry on non-2xx and I/O error, same eventId and same body. Re-sign with new timestamp if gateway checks a window (UNRESOLVED: gateway timestamp rule for webhooks).
- `callback.failFirstN`: first N deliveries return 503 (deterministic; avoids flaky random).
- `callback.sequence`: e.g. `[FAILED, SUCCESS]` for one payment, sent in order, to trigger ILLEGAL_TRANSITION paths. Ordering on wire is not guaranteed, so optionally `reorder` flag to send later events first.
- Early-webhook race: mock sends webhook while the create response is still open. Needs a `createResponseDelayMs` knob on the mock create endpoint (mock create endpoint not read). Simpler: `delayMs` small plus slow create, but this is a tuning-dependent race; a deterministic knob is better for tests.

Pitfalls: `@Async` retry loop blocking the webhook executor under load (use bounded pool); retries after gateway restart with same key still deduped (correct, test it); tests asserting timing instead of state.

## 4. JMeter (headless)

Layout (proposed):
```
loadtest/jmeter/
  create-pay-poll.jmx
  lib/            (none; Groovy bundled with JMeter)
  run-burst.sh    (jmeter -n -t ... -l results.jtl -e -o report/ -Jthreads=50 -Jbase=...)
  .gitignore      (results.jtl, report/)
```
Runner: `jmeter -n -t loadtest/jmeter/create-pay-poll.jmx -l out/results.jtl -e -o out/report -JTHREADS=50 -JHOST=localhost:8080` (flags -n -t -l -e -o are standard; UNVERIFIED against this session's docs fetch, which truncated).

Per-request HMAC (JSR223 PreProcessor, language groovy, child of HTTP Sampler):
```groovy
import javax.crypto.Mac; import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest
def body = sampler.getArguments().getArgument(0).getValue()   // exact sent bytes
def bodyHash = MessageDigest.getInstance("SHA-256").digest(body.getBytes("UTF-8")).collect{String.format("%02x",it)}.join()
def ts = vars.get("ts") ?: String.valueOf(System.currentTimeMillis())   // UNRESOLVED unit: check MerchantAuthFilter
def key = vars.get("idemKey")
def canonical = [sampler.getMethod(), sampler.getPath(), ts, key, bodyHash].join("\n")
def mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(props.get("merchantSecret").getBytes("UTF-8"), "HmacSHA256"))
def sig = mac.doFinal(canonical.getBytes("UTF-8")).collect{String.format("%02x",it)}.join()
sampler.getHeaderManager().add(new org.apache.jmeter.protocol.http.control.Header("X-Timestamp", ts))
sampler.getHeaderManager().add(new org.apache.jmeter.protocol.http.control.Header("X-Signature", sig))
```
Notes: `sampler.getPath()` must be the path without query (canonical is PATH). Body hash of empty body for GET poll is sha256 of "" (`e3b0c442...`). Use `props` (global) for the secret, not hard-coded in .jmx.

Same-request race (N threads, same key + body): compute signature once in a setUp Thread Group or in the thread group's first iteration and store in `vars`; reusing one timestamp is fine within 5-min window. Each thread still sends the request, which is the point.

Synchronizing Timer: in the thread group, place before the HTTP sampler. Set "Number of Simultaneous Users to Group" = N and a timeout (e.g. 10000 ms) so a missing thread does not hang the run (UNVERIFIED property names; the doc fetch was truncated before this section).
- Pitfall: Pre-Processors run before Timers in JMeter's order (UNVERIFIED from memory), so the signature timestamp is set before the release barrier. With a 5-min window this is harmless; if the window is tight, sign after the timer with a JSR223 Timer-free workaround.

Chain: create (POST /api/v1/payments, capture `$.id`, `$.payUrl` via JSON Extractor) -> pay at mock (`payUrl` path, UNRESOLVED: mock pay endpoint path) -> poll GET payment with Response Assertion on `SUCCEEDED` and a Duration Assertion. Use a While Controller with a Constant Timer (e.g. 200 ms) and a cap of attempts. Pitfall: poll before the webhook arrives; poll must bound retries or the thread hangs.

Metrics that identify first bottleneck (Spring Boot 4 + Hikari + Tomcat; metric names from Micrometer convention, UNVERIFIED this session):
- `hikaricp.connections.active` == `hikaricp.connections.max` sustained, `hikaricp.connections.pending` > 0: DB pool is the first bottleneck. Watch `hikaricp.connections.acquire` p99.
- `tomcat.threads.busy` near `tomcat.threads.config.max` (default 200): request threads blocked. Compare with pool state.
- `http.server.requests` p95/p99 per URI and status. 409 IN_PROGRESS share is expected under burst; 5xx must be 0.
- Error rate by status code in JMeter summary.
- Postgres: `pg_stat_activity` where `wait_event_type = 'Lock'`; `select count(*) from payments where order_id = ...` must be 1.
- Load generator: JMeter CPU. If JMeter saturates first, results are invalid. Run from a separate process with `-n` only.
- Note: provider HTTP runs outside any tx, so Hikari pending should not rise with mock latency. If it does, a tx is spanning the HTTP call (regression check).

Pitfalls (learner):
- Running GUI mode for measurements.
- Using `${__time()}` in a signature computed in a different sampler.
- Forgetting `-Jjmeter.save.saveservice.output_format=csv` defaults; check the report folder, it needs an empty output dir (`-o` must not exist or be empty).
- Counting 409s as failures. Classify: 201 and replay 201 = success; 409 IN_PROGRESS = expected; 5xx = fail.
- HTTP connection reuse: default "Use KeepAlive" fine; disable cookies manager to avoid session noise.

## 5. Per-topic pitfalls (summary)
- Fencing: no equality check on owner, guarding complete but not abandon, testing expiry by sleep, lease shorter than provider timeout.
- Webhooks: marking dedupe before deciding retryable, returning 200 on business rejection, assuming lock wait fixes early webhook, transitioning to SUCCEEDED before tx2 then failing tx2.
- Mock: retry with new eventId, unbounded retry on the async pool, timing-based tests.
- JMeter: GUI runs, signing wrong path (with query), counting 409 as errors, JMeter as bottleneck, hard-coded secret in `.jmx`.

## Unresolved questions
1. Zombie 0-row response: return its computed result, or 409? (user decision; trade-off above)
2. Epoch (canonical) or owner UUID? Pick one for learning goals.
3. Does provider accept our payment id as its reference (enables option d)? Check provider contract.
4. Early-webhook answer: 503 vs 409 vs 425 for `CREATED` payment, and for unknown payment id (404 or retry)?
5. Webhook timestamp window in gateway: does a retried mock delivery need re-signing?
6. `PaymentStateMachine` allowed transitions: is CREATED -> SUCCEEDED legal? (not read)
7. `X-Timestamp` unit (seconds vs ms) and canonical path rules in `MerchantAuthFilter` (not read).
8. Mock pay endpoint path and create-endpoint hook for a create-response delay (not read).
9. Adyen, PayPal, VNPAY, MoMo retry schedules: not verified (Adyen fetch 404; budget used).
10. JMeter Synchronizing Timer property names and CLI flag docs: doc fetch truncated; verify locally with `jmeter --help`.

Sources:
- https://martin.kleppmann.com/2016/02/08/how-to-do-distributed-locking.html
- https://brandur.org/idempotency-keys
- https://docs.stripe.com/webhooks
- https://jmeter.apache.org/usermanual/component_reference.html (truncated, Synchronizing Timer not read)
- Failed: https://docs.adyen.com/development-resources/webhooks/best-practices (404)

Status: DONE_WITH_CONCERNS
Summary: Recommend epoch (or owner token) fencing on complete/abandon, and for webhooks return retryable non-2xx with no dedupe row on CREATED/unknown payment, using merchantRef lookup. JMeter and mock knobs proposed; several gateway contracts unread.
Concerns/Blockers: Adyen/PayPal/VNPAY/MoMo retry claims UNVERIFIED; JMeter property names UNVERIFIED; 5-lookup budget reached; StateMachine, MerchantAuthFilter, mock controller not read (outside file list).
