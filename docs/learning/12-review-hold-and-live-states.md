# 12 - Giữ payment chờ duyệt: thêm một trạng thái "live"

> Increment 3a, phase 3. Mục tiêu: hiểu việc **thêm một trạng thái** vào state machine tốn gì ngoài một dòng trong enum — trạng thái **live** là gì, những chỗ nào trong hệ thống cùng "biết" danh sách trạng thái, và vì sao bước giữ payment phải nằm trong **một transaction** với bước tạo.

## 0. Ghi chú failure demo

Yêu cầu mới: action `REVIEW`. Payment khớp luật không bị từ chối mà được **giữ lại** ở trạng thái `UNDER_REVIEW` cho người duyệt, chưa gọi provider.

Bản đầu tiên chỉ làm "phần hiển nhiên": thêm `UNDER_REVIEW` vào enum, vào bảng transition, và nhánh `REVIEW` trong `create()`. Chạy `ReviewHoldIT` (đo 2026-11-26): **8 xanh, 2 đỏ**.

| Test | Bản "chỉ thêm enum" | Sau khi sửa |
|---|---|---|
| `aHeldPaymentStillOccupiesItsOrder` | **đỏ**: `Status expected:<409> but was:<201>` — tạo lại cùng order được **payment thứ hai** | 409 `ORDER_ALREADY_EXISTS` |
| `aHeldPaymentExpiresWhenTheReviewDeadlinePasses` | **đỏ**: `expected:<EXPIRED> but was:<UNDER_REVIEW>` — payment bị giữ **mãi mãi** | `EXPIRED`, reason `REVIEW_TIMEOUT` |

Cả hai đều không phải lỗi logic của code mới. Chúng là hai chỗ **cũ** có danh sách trạng thái riêng và không ai báo cho chúng biết có trạng thái mới. Compiler không giúp được: một cái nằm trong SQL, một cái là câu query nhận tham số.

## 1. "Live" nghĩa là gì

Một payment là **live** khi nó **vẫn còn có thể kết thúc bằng việc thu được tiền**.

| Trạng thái | Live? | Vì sao |
|---|---|---|
| `CREATED` | có | đang giữa hai transaction của create |
| `UNDER_REVIEW` | **có** | người duyệt có thể cho qua (phase 4) |
| `PENDING` | có | khách có thể trả |
| `SUCCEEDED` | có (đã thu) | tạo lại là thu tiền lần hai |
| `FAILED`, `EXPIRED`, `CANCELLED` | không | merchant được phép tạo lại order |

"Live" không phải một field. Nó là một **khái niệm rải ở nhiều nơi**, mỗi nơi viết lại danh sách theo cách của mình:

| Nơi | Viết ở dạng | Quên cập nhật thì |
|---|---|---|
| `PaymentStateMachine.ALLOWED` | Java `EnumMap` | trạng thái mới không đi đâu được (lỗi ngay, dễ thấy) |
| `ux_payments_live_order` | predicate của partial index, **SQL** | hai payment live cho một order (lỗi im lặng) |
| `PaymentSweepService.expireOverdue()` | tham số `status` của query | payment kẹt mãi (lỗi im lặng) |
| `load-tests/verify-invariants.sql` | `status in (...)` | báo cáo bất biến nói dối: không đếm payment trùng |
| `WebhookPaymentApplier.decideWithoutProviderRef` | `if` theo status | (ở đây đúng sẵn, xem mục 5) |

Bài học chính của phase này: **thêm một trạng thái là một thay đổi xuyên hệ thống**. Việc phải làm là `grep` mọi chỗ nhắc tên trạng thái — kể cả `.sql` — chứ không phải tin vào compiler.

## 2. Sửa index: drop và tạo lại

Predicate của partial index không `ALTER` được, nên `V11`:

```sql
DROP INDEX ux_payments_live_order;
CREATE UNIQUE INDEX ux_payments_live_order
    ON payments (merchant_id, order_id)
    WHERE status IN ('CREATED', 'UNDER_REVIEW', 'PENDING', 'SUCCEEDED');
```

- Flyway chạy cả file trong **một transaction**: không có khoảnh khắc nào bảng thiếu guard.
- Cái giá: trong lúc dựng index, mọi lệnh ghi vào `payments` bị chặn. Với bảng nhỏ là tức thời. Bảng lớn thì làm khác: `CREATE UNIQUE INDEX CONCURRENTLY` dưới tên mới (không chạy được trong transaction), rồi mới drop cái cũ.
- Nếu dữ liệu hiện có đã vi phạm predicate mới thì `CREATE UNIQUE INDEX` thất bại và migration rollback. Ở đây không thể: trước `V11` chưa có dòng `UNDER_REVIEW` nào.

Vì sao test đầu vẫn tạo được payment thứ hai dù `CREATED` có trong index cũ: dòng thứ nhất đã là `UNDER_REVIEW` (ngoài index cũ) trước khi dòng thứ hai được insert.

## 3. Giữ payment: insert và chuyển trạng thái trong một transaction

```
create(): decision = REVIEW
  tx1 { insert payments (CREATED, expires_at = now + review-timeout)
        transition CREATED -> UNDER_REVIEW   (reason = reason_code của luật, source SYSTEM)
        provider_code = provider đã resolve
        insert risk_decisions (REVIEW, payment_id) }
  trả 201 { status: UNDER_REVIEW, payUrl: null }       ← không gọi provider
```

Nếu tách làm hai transaction (insert `CREATED`, commit, rồi mới chuyển):

- JVM chết ở giữa → còn lại một dòng `CREATED` trông y hệt một create bị bỏ dở. Sau 2 phút `failAbandonedCreated()` chuyển nó thành `FAILED CREATE_ABANDONED`. Payment đáng lẽ chờ duyệt lại bị hệ thống tự hủy, với lý do sai.
- Cả khi không chết, có một khoảng thời gian dòng đó *nhìn thấy được* ở `CREATED`.

Gộp vào một transaction thì bên ngoài chỉ thấy **hoặc không có gì, hoặc `UNDER_REVIEW`** (`theAbandonedCreateSweepLeavesAHeldPaymentAlone`). Luồng thường (`ALLOW`) *buộc* phải tách hai transaction vì có lời gọi HTTP ở giữa; luồng giữ thì không có lời gọi nào nên không có lý do để tách.

Vẫn insert `CREATED` rồi mới chuyển (thay vì insert thẳng `UNDER_REVIEW`) để mọi thay đổi trạng thái tiếp tục đi qua `PaymentStateMachine.transition` và để lại một dòng ledger nói **luật nào** đã giữ:

```
GET /api/v1/payments/{id}
  "transitions": [ { "from": "CREATED", "to": "UNDER_REVIEW", "reasonCode": "HIGH_AMOUNT", "source": "SYSTEM" } ]
```

## 4. Payment bị giữ kết thúc thế nào

| Bước chuyển | Ai | Reason |
|---|---|---|
| `UNDER_REVIEW → CANCELLED` | merchant (`POST .../cancel`) | `MERCHANT_CANCEL` |
| `UNDER_REVIEW → EXPIRED` | sweeper, khi quá `expires_at` | `REVIEW_TIMEOUT` |
| `UNDER_REVIEW → FAILED` | operator từ chối | phase 4 |
| `UNDER_REVIEW → CREATED` | operator duyệt | phase 4, **chưa có** |

`cancel()` không sửa dòng nào: nó vốn chỉ hỏi state machine, và bảng transition giờ cho phép. Đó là lợi ích của việc để luật chuyển trạng thái ở **một** chỗ.

Bị cấm: `UNDER_REVIEW → PENDING` và `→ SUCCEEDED`. Payment bị giữ chưa từng tới provider, nên không thể "đang chờ trả" hay "đã trả" (`PaymentStateMachineTest.aHeldPaymentCannotBecomePayableOrPaid`).

**Hạn chót.** `expires_at` của payment bị giữ là `now + fluxpay.risk.review-timeout` (30 phút), không phải `fluxpay.payment.expiry` (15 phút): cùng một cột, nghĩa khác theo trạng thái. Sweeper tìm theo `expires_at` nên chỉ cần quét thêm một trạng thái.

Không có hạn chót thì hàng chờ duyệt chỉ dài ra, và mỗi payment trong đó **giữ order của merchant** vô thời hạn (mục 1: nó là live).

## 5. Webhook không chạm được vào payment bị giữ

Provider chưa bao giờ được gọi, nên `provider_ref` là null và không webhook hợp lệ nào nhắc tới payment này. Nếu vẫn có một event trỏ tới nó qua `merchantRef`, `decideWithoutProviderRef` thấy "không phải `CREATED`, không có `provider_ref`" → từ chối `PAYMENT_NEVER_ACCEPTED`, payment giữ nguyên (`aWebhookCannotMoveAHeldPayment`). Kể cả khi nhánh đó cho qua, state machine vẫn chặn `UNDER_REVIEW → SUCCEEDED`. Hai lớp, lớp nào cũng đủ.

## 6. Hợp đồng API đổi

- `POST /api/v1/payments` có thể trả `201` với `status = "UNDER_REVIEW"` và **`payUrl = null`**. Merchant nào mặc định "201 thì có `payUrl` để redirect" sẽ hỏng. `expiresAt` lúc này là hạn duyệt.
- `GET` có giá trị `status` mới.
- Cùng `Idempotency-Key` → replay đúng body `UNDER_REVIEW` đó, **kể cả sau khi payment đã đổi trạng thái**; merchant phải `GET` để biết trạng thái hiện tại.

Thêm một giá trị enum vào response là **breaking change** với client nào `switch` trên status mà không có nhánh mặc định.

## Giới hạn đã biết

- **Chưa duyệt được.** Payment bị giữ chỉ có thể bị hủy hoặc hết hạn; approve/reject của operator là phase 4.
- **Không báo cho merchant** khi payment bị giữ kết thúc; merchant phải poll `GET` (push đến cùng Kafka ở increment 3).
- Luật seed giữ **mọi** payment trên 20.000.000: không phân biệt merchant.
- Danh sách trạng thái live vẫn nằm ở nhiều nơi (mục 1). Không có test nào tự phát hiện nơi thứ sáu nếu sau này có.

## Tự kiểm bằng Postman / psql

Folder **Phase 12 - Review hold** (dùng biến `reviewOrder`), chạy theo thứ tự:

1. Create 30.000.000 → `201`, `status = UNDER_REVIEW`, `payUrl = null`.
2. Get → một dòng transition `CREATED → UNDER_REVIEW`, reason `HIGH_AMOUNT`.
3. Create lại cùng order (key mới) → `409 ORDER_ALREADY_EXISTS`.
4. Cancel → `CANCELLED`. Sau bước này chạy lại bước 1 được.

Xem hết hạn mà không chờ 30 phút: giữ một payment (bước 1), rồi `update payments set expires_at = now() - interval '1 minute' where status = 'UNDER_REVIEW';` và chờ một lượt sweeper (30s) → `GET` trả `EXPIRED`, reason `REVIEW_TIMEOUT`.

## Tự phá thử (bài tập)

1. Đổi nội dung `V11` thành `SELECT 1;` (trên DB sạch: `docker compose down -v`) rồi chạy `ReviewHoldIT`. Test nào đỏ? Chạy `load-tests/verify-invariants.sql` sau khi tạo hai payment giữ cho cùng order: dòng `orders_with_more_than_one_live_payment` nói gì, và nó sẽ nói gì nếu file đó chưa được cập nhật?
2. Trong `PaymentSweepService.expireOverdue()` bỏ lượt quét `UNDER_REVIEW`. Ngoài test hết hạn, hậu quả nghiệp vụ với **order của merchant** là gì?
3. Tách nhánh giữ trong `create()` thành hai transaction (insert rồi commit, sau đó mới transition). Viết một test dùng `MutableClock` + `failAbandonedCreated()` chứng minh payment có thể bị `FAILED CREATE_ABANDONED`. (Gợi ý: cần dừng được giữa hai transaction.)
4. Thêm `UNDER_REVIEW → PENDING` vào `ALLOWED`. Test nào đỏ? Mô tả một payment `PENDING` không có `payUrl` trông thế nào từ phía merchant.
5. Thêm luật `REVIEW` có `priority = 50` với điều kiện `amount > 40000000`. Payment 60.000.000 giờ bị giữ hay bị từ chối? Vì sao điều đó có thể là một lỗ hổng?
