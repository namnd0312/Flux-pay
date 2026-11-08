# 05 - Idempotency: an toàn khi retry

> Phase 6. Mục tiêu: hiểu vì sao client **phải** retry, retry ngây thơ gây **double-charge** thế nào, và vì sao **ràng buộc trong database** (không phải check trong application code) mới cứu được.

## 0. Ghi chú failure demo

Trước phase 6, mỗi `POST /api/v1/payments` tạo một payment mới. Demo phá: ký **một** request trong Postman, chép header + body vào `scripts/burst-replay.sh`, bắn **50 request giống hệt, 20 cái song song**, tất cả trong cửa sổ 5 phút (chữ ký vẫn hợp lệ).

- **Trước:** `select count(*) from payments where order_id='ORD-BURST-1'` → **50**. Năm mươi pay URL cho một đơn, mock-provider mở 50 session. Đây là **double-charge** (ở đây là 50-charge).
- **Sau:** cùng burst đó → **1** payment row, **1** provider call; 49 cái còn lại là replay (`201` + header `Idempotency-Replayed: true`) hoặc `409 IDEMPOTENCY_IN_PROGRESS`.

Cái "sau" được đóng khung chắc trong test `ConcurrentCreateIT` (50 thread, cùng key + body → đúng 1 payment, `CountingFakeProvider.calls() == 1`).

## 1. Vì sao client retry (và vì sao ta không cấm được)

Mạng không đáng tin, nhất là mobile: client gửi create, **timeout** trước khi nhận response, nhưng server **đã** tạo payment. Client không biết thành công hay chưa → **retry**. Đây là hành vi đúng của client; server phải chịu được. Quy tắc: **thao tác tạo tiền phải idempotent** — gửi lại nhiều lần chỉ có hiệu lực **một** lần.

**Idempotency** = gọi N lần cho kết quả (và tác dụng phụ) giống hệt gọi 1 lần.

## 2. Vì sao `orderId` unique một mình là **chưa đủ**

Ý tưởng đầu: unique `(merchant_id, order_id)`, trùng thì 409. Hai vấn đề:

1. **Retry sau 502 cần *cùng câu trả lời*, không phải 409.** Nếu provider lỗi và ta trả 502, merchant retry **đúng request đó** — họ cần biết *kết quả thật* (thành công? thất bại?), chứ 409 "đơn đã tồn tại" không trả lời được. Idempotency key lưu lại **response gốc** để phát lại.
2. **Đơn FAILED/EXPIRED/CANCELLED phải tạo lại được.** Nếu unique cứng trên mọi trạng thái, một lần thử hỏng sẽ **khóa vĩnh viễn** orderId đó. Red-team finding #1. Nên index unique chỉ áp cho trạng thái **còn sống**:

```sql
CREATE UNIQUE INDEX ux_payments_live_order ON payments (merchant_id, order_id)
  WHERE status IN ('CREATED','PENDING','SUCCEEDED');
```

Đây là **tấm chắn nghiệp vụ thứ hai**: chặn *key khác nhau, cùng orderId* khi đang có đơn sống → `409 ORDER_ALREADY_EXISTS`. Idempotency key chặn *cùng key* (retry y hệt). Hai lớp bổ sung nhau.

## 3. Thiết kế: key per merchant + request hash

Header `Idempotency-Key` (bắt buộc trên create, ≤ 64 ký tự, thiếu → `400 IDEMPOTENCY_KEY_MISSING`). Khóa đã **nằm trong chữ ký HMAC** (phase 5, canonical string), nên không giả mạo được.

Bảng `idempotency_keys` khóa chính `(merchant_id, idempotency_key)`, lưu:
- `request_hash` = sha256 của body thô → phát hiện **dùng lại key cho body khác** → `422 IDEMPOTENCY_KEY_REUSED`.
- `status` = `IN_PROGRESS` | `COMPLETED`, `lease_until`, `response_status`, `response_body`, `expires_at`.

Bốn kết cục của `begin()`:

| Tình huống | Kết cục | HTTP |
|---|---|---|
| Chưa có key | NEW → chạy request | (kết quả thật) |
| Key COMPLETED, cùng body | REPLAY response đã lưu | status đã lưu + `Idempotency-Replayed: true` |
| Key IN_PROGRESS, lease còn hạn | đang chạy ở nơi khác | `409 IDEMPOTENCY_IN_PROGRESS` |
| Key tồn tại, body khác | lạm dụng key | `422 IDEMPOTENCY_KEY_REUSED` |

## 4. IN_PROGRESS là **lease**, không phải lock

Khi một request đang chạy, key ở `IN_PROGRESS` với `lease_until = now + 60s`. Nếu worker **chết giữa chừng** (JVM crash) mà không `complete`, một lock vĩnh viễn sẽ **khóa cứng** key đó mãi mãi. Nên ta dùng **lease có hạn**: retry đến khi lease còn hạn → `409`; lease **hết hạn** → request mới **giành quyền** (`UPDATE ... WHERE lease_until < now RETURNING`) và chạy lại. "Worker cũ coi như đã chết."

### Giới hạn đã biết (để dành increment 2)

`complete()`/`abandon()` hiện ghi theo `(merchant, key)` **không kèm kiểm tra chủ lease**. Kịch bản hiếm: worker A giành key, **treo** quá 60s (GC/chậm, chưa chết); worker B giành lại lease và chạy. Lúc này *payment* vẫn **đúng một** (tx1 của B đụng `ux_payments_live_order` nếu A đã kịp tạo row → B nhận 409; hoặc ngược lại) — **không hề double-charge**. Nhưng *status lưu để replay* có thể là của A hay B tùy thứ tự ghi. Hệ quả tối đa: một lần replay trả status hơi lệch (hoặc một retry nhận 409). Khắc phục triệt để = **lease fencing** (gắn token/epoch cho lease, `complete` chỉ ghi nếu còn là chủ) — để dành **increment 2 (concurrency hardening)**, nơi các race sâu hơn được xử lý cùng optimistic lock.

## 5. Vì sao **database quyết**, không phải application code

Mấu chốt chống race: **không** `exists-check-rồi-insert` trong Java. Dưới 50 request song song, hai thread cùng thấy "chưa có key" rồi cùng insert → hai payment. Thay vào đó:

```
INSERT ... ON CONFLICT (merchant_id, idempotency_key) DO NOTHING RETURNING 1
```

**Khóa chính của database là trọng tài duy nhất.** Đúng một thread insert được (RETURNING có dòng) → NEW; các thread thua **block** tới khi thread thắng commit, rồi `DO NOTHING` → đọc dòng đã có → `409`/replay. Giành lease hết hạn cũng vậy: `UPDATE ... WHERE lease_until < now RETURNING` — chỉ một updater thắng. Không hề có lock trong bộ nhớ JVM. (Giống hệt bài học webhook phase 4: ràng buộc DB, không exists-check.)

## 6. Vì sao đặt ở **filter**, không phải trong controller

Những response đáng phát lại gồm cả thứ controller **không tự trả**: `502` (provider lỗi) và `409` (đơn trùng) do `ProblemDetailsHandler` dựng **sau khi** controller đã ném exception. Chỉ một **response wrapper** (`ContentCachingResponseWrapper`) mới thấy status + body cuối cùng. Nên `IdempotencyFilter`:

- Chạy **sau** `MerchantAuthFilter` (đã có `MerchantContext`), chỉ trên đúng path `POST /api/v1/payments`.
- NEW → bọc response, `chain.doFilter`, rồi: status ∈ `{201,400,404,409,422,502}` → `complete(status, body)`; ngược lại (`500`,`503`) → `abandon` (xóa key, cho retry). **Luôn** `copyBodyToResponse()` trong `finally`.
- REPLAY → ghi lại response đã lưu, **không** chạm controller → **không** gọi provider lần nữa (test khẳng định `calls()` không tăng).

**Lưu gì:** status + body. Chỉ lưu các status **xác định** (retry sẽ cho đúng kết quả đó). Lỗi tạm thời (500/503) không lưu → merchant retry được bằng chính key đó. TTL 24h, sweeper `purgeExpired()` dọn.

So sánh ngành: Stripe dùng `Idempotency-Key` y hệt (lưu response, replay, TTL 24h); VNPAY/MoMo dựa nhiều hơn vào `orderId` duy nhất phía merchant. Ta làm cả hai lớp.

## Tự phá thử (bài tập)

Thay trong `IdempotencyStore.claim(...)` cái `INSERT ... ON CONFLICT DO NOTHING` bằng kiểu application-check:

```java
if (find(merchantId, key).isPresent()) return false;   // check
// ... rồi mới insert (act)
```

Chạy lại `scripts/burst-replay.sh` (hoặc `ConcurrentCreateIT`): **payment trùng xuất hiện lại**. Giải thích: giữa `check` và `act`, nhiều thread cùng lọt qua `check` vì chưa ai `insert` xong — **check-then-act thua ràng buộc database**. Đây chính là cây cầu sang increment 2 (concurrency: optimistic lock `@Version`, `SELECT ... FOR UPDATE`, và các race sâu hơn).
