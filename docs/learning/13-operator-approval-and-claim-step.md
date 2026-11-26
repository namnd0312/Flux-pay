# 13 - Operator duyệt payment: bước claim trước khi gọi ra ngoài

> Increment 3a, phase 4. Mục tiêu: hiểu vì sao một hành động "kiểm tra rồi gọi hệ thống ngoài" cần một bước **claim** đứng trước lời gọi, nó giống hệt cách `create()` chia hai transaction thế nào, vì sao sweeper phải đổi cột thời gian, và một **shared admin token** bảo vệ được gì, không bảo vệ được gì.

## 0. Ghi chú failure demo

Ba chỗ hỏng, đều đo/chạy thật ngày 2026-11-26.

**(1) Approve ngây thơ: đọc trạng thái → gọi provider → ghi `PENDING`.**

```java
var p = payments.findById(id);
if (p.getStatus() != UNDER_REVIEW) throw ...;        // check
var result = provider.createPayment(...);            // act (HTTP, chậm)
tx { ...; transition(p, PENDING) }                   // ghi
```

30 payment bị giữ, mỗi payment 10 request approve cùng lúc (`ReviewApproveRaceIT`):

| | Ngây thơ | Có bước claim |
|---|---|---|
| Phiên thanh toán mở ở provider | **298** (đáng lẽ 30) | 30 |
| Request trả 200 | 30 | 30 |
| Request trả 5xx | **262** | 0 (270 request còn lại trả 409) |

Database vẫn "đúng": mỗi payment chỉ một dòng `PENDING`. Cái hỏng nằm **ngoài** database: 268 phiên thanh toán mồ côi ở provider, mỗi phiên là một link mà khách có thể trả tiền. `@Version` chặn được ghi đè, không rút lại được lời gọi HTTP đã đi.

**(2) Sweeper giết payment đang được duyệt.** `theAbandonedCreateSweepDoesNotFailAPaymentThatIsBeingApproved`: giữ payment 5 phút, approve, và trong lúc lời gọi provider đang bay thì `failAbandonedCreated()` chạy. Với query cũ (`created_at < now - 2m`) payment bị `FAILED CREATE_ABANDONED` giữa chừng: provider đã có phiên, gateway thì coi là hỏng.

**(3) Approve muộn cho khách một link sắp chết.** `approveGivesTheCustomerAFullPaymentWindow`: approve ở phút 29 của 30 phút giữ, không đặt lại `expires_at` → payment `PENDING` hết hạn sau **1 phút**.

## 1. Bước claim

```
approve(id):
  claim  tx { đọc; phải là UNDER_REVIEW
              resolve provider theo provider_code
              transition UNDER_REVIEW -> CREATED   (REVIEW_APPROVED, OPERATOR)
              expires_at = now + fluxpay.payment.expiry }        ← commit
  provider.createPayment(...)                                    ← không có transaction
  tail   tx { ghi provider_ref / pay_url; CREATED -> PENDING }
```

**Claim** là một lần ghi nhỏ, commit **trước** hành động đắt tiền, nói rằng "việc này là của tôi". Chỉ **một** người commit được nó:

- Hai approve cùng đọc `UNDER_REVIEW`. Người commit trước thắng. Người kia thua `@Version`, `OptimisticRetry` mở transaction mới, **đọc lại**, thấy `CREATED` → `IllegalPaymentTransitionException` → **409**. Nó chưa gọi provider.
- Ai đến sau hẳn thì thấy `CREATED` hoặc `PENDING` ngay lần đọc đầu → 409.

Quy tắc chung: **đừng "check rồi act" khi act là thứ không rollback được**. Biến check thành một lần *ghi có điều kiện* (claim), để database chọn người thắng, rồi chỉ người thắng mới act. Đã gặp cùng mẫu này ba lần: `INSERT ... ON CONFLICT` của idempotency key (note 05), fencing token (note 08), và bây giờ.

Luật **không** được đánh giá lại lúc approve: quyết định của operator thay cho luật.

## 2. Đây chính là `create()` chạy lại nửa sau

| | `create()` | `approve()` |
|---|---|---|
| Bước ghi trước lời gọi | tx1: insert `CREATED` | claim: `UNDER_REVIEW → CREATED` |
| Lời gọi ra ngoài | `provider.createPayment`, không transaction | y hệt |
| Bước ghi sau | tx2: `CREATED → PENDING` | y hệt |
| Provider lỗi | compensate → `FAILED`, 502 | y hệt |
| JVM chết ở giữa | còn `CREATED`, sweeper dọn | y hệt |

Vì thế approve đưa payment về **`CREATED`** chứ không nhảy thẳng `UNDER_REVIEW → PENDING`: từ `CREATED` trở đi mọi thứ đã có sẵn và đã được test. Phần chung được tách thành `PaymentProviderHandoff.submit(...)`, cả hai đường cùng gọi, không có hai bản logic compensate.

`UNDER_REVIEW → PENDING` vẫn bị cấm trong bảng transition (`PaymentStateMachineTest.aHeldPaymentCannotBecomePayableOrPaid`).

## 3. Reject: bảng transition không đủ

```java
var p = held(id, FAILED);     // ném 409 nếu không còn UNDER_REVIEW
stateMachine.transition(p, FAILED, "RISK_REVIEW_REJECTED", OPERATOR);
```

Vì sao cần `held(...)` khi đã có state machine: `PENDING → FAILED` là bước **hợp lệ** (provider báo thất bại). Nếu reject chỉ dựa vào bảng, một lệnh reject đến muộn — sau khi người khác đã approve — sẽ `FAILED` một payment khách đang trả được. Bảng transition trả lời "bước này có bao giờ hợp lệ không"; nó không trả lời "**hành động này** có được làm bước đó không". Test: `aDecisionCanBeMadeOnlyOnce`.

## 4. Vì sao sweeper phải đổi cột

`failAbandonedCreated()` tìm dòng `CREATED` "quá cũ". Trước đây "cũ" tính bằng `created_at`, đúng khi `CREATED` chỉ là trạng thái đầu đời. Giờ payment có thể **quay lại** `CREATED` sau 25 phút bị giữ: theo `created_at` nó đã "bị bỏ rơi" từ lâu ngay khoảnh khắc được claim.

Câu hỏi đúng là "dòng này **ở trong `CREATED`** bao lâu rồi", tức `updated_at` (state machine `touch` nó ở mỗi transition):

```sql
select id from payments where status = 'CREATED' and updated_at < :before
```

- Payment mới: `updated_at = created_at` khi còn `CREATED` → hành vi cũ không đổi, các test cũ không phải sửa.
- Approve chết sau claim: vẫn được dọn sau 2 phút tính từ lúc claim (`anApprovalThatDiedAfterTheClaimIsStillCleanedUp`).

Rủi ro mới: bất kỳ code nào `touch` một dòng `CREATED` sẽ kéo dài thời gian ân hạn của nó. Hiện chỉ state machine gọi `touch`.

## 5. Các cuộc đua

`ReviewApproveRaceIT`, mỗi kịch bản 40 payment, hai bên được thả cùng lúc bằng `CyclicBarrier`:

| Approve đua với | Kết cục hợp lệ |
|---|---|
| 9 approve khác | đúng 1 × 200, 9 × 409, 1 provider call, `PENDING` |
| merchant cancel | approve 200 + cancel 409 → `PENDING` (cancel rơi vào cửa sổ `CREATED`); approve 200 + cancel 200 → `CANCELLED` (cancel tới sau khi approve xong); approve 409 + cancel 200 → `CANCELLED` |
| operator reject | approve 200 + reject 409 → `PENDING`; hoặc approve 409 + reject 200 → `FAILED` |
| review timeout (sweeper) | approve 200 → `PENDING`; hoặc approve 409 → `EXPIRED` |

Với mọi payment: provider được gọi **đúng 1 lần nếu approve thắng, 0 lần nếu thua**; không có 5xx; ledger xếp được thành một chuỗi bước hợp lệ từ `CREATED` tới đúng trạng thái đang lưu.

Ai thắng là may rủi. Cái không được phụ thuộc may rủi là **người thua được đối xử thế nào**.

Sweeper không dùng `OptimisticRetry`: thua `@Version` thì nó bỏ dòng đó, lượt sau xét lại.

## 6. Admin token

`/internal/*` có filter riêng (`AdminTokenFilter`), header `X-Admin-Token`:

- So sánh bằng `MessageDigest.isEqual`: thời gian không phụ thuộc vị trí byte sai đầu tiên, nên không đoán dần được token qua thời gian phản hồi. (`String.equals` dừng ở byte sai đầu tiên.)
- Mọi lần từ chối là cùng một `401 ADMIN_AUTH_FAILED`: không nói thiếu hay sai.
- Token cấu hình trống → **ứng dụng không khởi động**. Nếu không, biến môi trường quên đặt sẽ lặng lẽ nghĩa là "token rỗng là hợp lệ".
- Hai loại credential **không chồng lên nhau** (`AdminTokenFilterIT`): chữ ký HMAC hợp lệ của merchant không mở được `/internal`; admin token không mở được `/api`.

Các endpoint này tra theo payment id **không kèm merchant** — chúng chỉ an toàn vì nằm sau filter này.

## Giới hạn đã biết

- **Shared token, không có danh tính.** Ledger chỉ ghi `source = OPERATOR`: biết *có người duyệt*, không biết *ai*. Không thu hồi được quyền của một người mà không đổi token cho tất cả. Token đi trong header dạng rõ: chỉ an toàn sau TLS và trong mạng nội bộ. Đã quyết định để ngoài phạm vi.
- **Chết giữa claim và provider** → payment nằm ở `CREATED`, sau 2 phút bị `FAILED CREATE_ABANDONED`. Operator đã bấm duyệt nhưng merchant thấy `FAILED` và phải tạo lại. Tệ hơn: nếu chết **sau khi** provider đã trả lời, ở provider có một phiên mồ côi (giống hệt create thường; cần reconciliation, increment 4).
- **Cancel trong cửa sổ `CREATED`** (giữa claim và tail) trả 409 dù payment sắp `PENDING`; merchant phải thử lại.
- **Approve được cả khi đã quá hạn duyệt** nếu sweeper chưa kịp quét: ai commit trước thì thắng.
- **`GET /internal/reviews` trả tối đa 100 dòng**, không phân trang.
- **Merchant không được báo** khi payment được duyệt hay bị từ chối; phải poll `GET`. Idempotent replay của create vẫn trả body `UNDER_REVIEW` cũ.
- Dưới đồng hồ test các dòng ledger của một payment có thể trùng `created_at`; `GET` sắp theo `created_at` nên thứ tự hiển thị khi đó không xác định. Với đồng hồ thật các bước cách nhau ít nhất một lời gọi provider.

## Tự kiểm bằng Postman

Thêm `FLUXPAY_ADMIN_TOKEN=dev-admin-token-change-me` vào `.env` (xem `.env.example`) rồi `docker compose up --build`. Import lại collection và environment (biến mới `adminToken`). Folder **Phase 13 - Review**, chạy theo thứ tự:

1. Create 30.000.000 → `UNDER_REVIEW`.
2. `GET /internal/reviews` → thấy payment, `holdReason = HIGH_AMOUNT`.
3. Approve → `200`, `PENDING`, có `payUrl`; ledger 3 dòng.
4. Approve lần nữa → `409 PAYMENT_ILLEGAL_TRANSITION`.
5. Cancel (dọn dẹp, giải phóng order).
6. Create lại → `UNDER_REVIEW`.
7. Reject → `200`, `FAILED`, reason `RISK_REVIEW_REJECTED`.
8. `GET /internal/reviews` không kèm token → `401 ADMIN_AUTH_FAILED`.

## Tự phá thử (bài tập)

1. Trong `PaymentReviewService.approve`, chuyển lời gọi `handoff.submit(...)` vào **trong** lambda của claim. Test nào đỏ, và `CountingFakeProvider` báo gì? Ngoài test, điều gì xảy ra với connection pool khi provider chậm 5 giây?
2. Bỏ `held(...)` trong `reject` (chỉ gọi `stateMachine.transition`). Test nào đỏ? Viết ra chuỗi sự kiện mà khách **đã trả tiền** nhưng payment thành `FAILED`.
3. Đổi query abandoned về `p.createdAt`. Chỉ một test đỏ. Vì sao các test cũ của increment 1-2 vẫn xanh?
4. Bỏ `p.setExpiresAt(...)` trong claim. Ngoài test "full payment window", hãy mô tả điều merchant thấy khi approve ở phút 29.
5. Thay `MessageDigest.isEqual` bằng `Arrays.equals`. Test nào đỏ? Nếu không có test nào đỏ, điều đó nói gì về việc dùng test để chứng minh tính chất bảo mật về thời gian?
6. Trong `ReviewApproveRaceIT.approveAgainstMerchantCancel`, có một kết cục mà **cả hai** bên nhận 200. Đó có phải lỗi không? Provider đã được gọi mấy lần, và ai chịu phiên thanh toán đó?
