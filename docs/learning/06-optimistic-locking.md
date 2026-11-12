# 06 - Optimistic locking: thua cuộc đua thì đọc lại, đừng sập

> Increment 2, phase 1. Mục tiêu: hiểu **lost update** là gì, `@Version` biến nó thành lỗi nhìn thấy được như thế nào, và vì sao **phát hiện xung đột chưa đủ** — phải **xử lý** kẻ thua cho đúng.

## 0. Ghi chú failure demo

Từ increment 1, `Payment` đã có `@Version` nhưng **không ai bắt** exception của nó. Hai writer cùng đụng một payment PENDING: webhook SUCCESS của provider và `cancel` của merchant.

Demo: `WebhookVsCancelRaceIT` tạo 200 payment PENDING; với mỗi cái, hai thread được một `CyclicBarrier` thả **cùng lúc** — một bên POST webhook đã ký, một bên POST cancel.

- **Trước (đo 2026-11-12):** 200 cuộc đua → **199 cuộc có HTTP 500**, **195 webhook event kẹt ở `RECEIVED`**. Webhook thắng 4 lần, cancel thắng 196 lần.
- **Sau:** cùng test đó → **0** lỗi 5xx, **0** event kẹt, mỗi payment đúng **một** dòng terminal trong `payment_transitions`. Test chạy xanh trong cả bộ `./mvnw verify` (65 unit + 41 IT).

Vì sao event kẹt `RECEIVED` là **mất tiền** chứ không chỉ là xấu: dòng dedup đã được insert, nên khi provider gửi lại, gateway trả "duplicate" và bỏ qua. Ở cuộc đua này payment đã CANCELLED nên kết quả cuối vẫn đúng, nhưng cùng cơ chế đó sẽ nuốt mất một webhook hợp lệ ở phase 2.

Cơ chế tối giản, không dựa vào may rủi, nằm trong `OptimisticLockConflictIT`: dùng latch ép thứ tự "A đọc → B đọc, ghi, commit → A ghi". Kết quả mỗi lần chạy: code của A chạy **hết** không lỗi, rồi A nhận `ObjectOptimisticLockingFailureException` lúc commit (flush) — test assert cả hai điều đó; trạng thái cuối là của B, `version` = 2, và audit row của A **rollback cùng** với status của A.

## 1. Lost update: lỗi không báo lỗi

Mẫu **read-modify-write**: đọc dòng, quyết định trong application, ghi lại.

```
T1 (cancel):  đọc payment (PENDING)
T2 (webhook): đọc payment (PENDING)
T2:           ghi SUCCEEDED, commit
T1:           ghi CANCELLED, commit      ← đè lên kết quả của T2
```

Cả hai đều qua được state machine vì cả hai đều **thấy PENDING** lúc đọc. Không có `@Version`, T1 ghi đè lặng lẽ: khách đã trả tiền, payment lại CANCELLED, audit ledger có **hai** dòng terminal. Đây là **lost update**, và nó nguy hiểm vì không có exception nào.

State machine **không** chặn được chuyện này một mình: nó kiểm tra trên bản sao trong bộ nhớ, mà bản sao đó đã cũ.

## 2. `@Version` làm gì

Hibernate đổi câu UPDATE thành:

```sql
update payments set status = ?, version = 2, ... where id = ? and version = 1
```

Nếu ai đó đã commit trước, `version` trong DB là 2 → câu UPDATE khớp **0 dòng** → Hibernate ném lỗi (`StaleObjectStateException`), Spring dịch thành `ObjectOptimisticLockingFailureException` (cha là `OptimisticLockingFailureException` — bắt class cha).

Gọi là **optimistic** vì không khóa gì lúc đọc: cứ làm, đến lúc ghi mới kiểm tra "dòng này còn như lúc tôi đọc không?". Vẫn là nguyên tắc cũ của note 05: **database quyết** (điều kiện nằm ngay trong câu ghi), không phải `if` trong application.

Lưu ý thời điểm: lỗi nổ lúc **flush**, thường là lúc commit — tức là *sau khi* code trong transaction đã chạy xong. Toàn bộ transaction rollback, kể cả audit row.

## 3. Phát hiện chưa đủ: phải xử lý kẻ thua

Trước phase này, exception đi thẳng lên: cancel → 500, webhook → 500 + event kẹt. Phát hiện được xung đột nhưng trả lời sai.

Kẻ thua nên làm gì? **Đọc lại rồi thử lại**, không phải gửi lại dữ liệu cũ:

- Bản entity nó đang cầm đã **stale** (version cũ). Thử lại với đúng object đó thì thua mãi.
- Persistence context của transaction hỏng không dùng lại được. Cần **transaction mới**.

`OptimisticRetry.execute(path, tx, work)` làm đúng hai việc đó: mỗi lần thử mở một transaction mới, và `work` **tự load** payment bên trong. Bốn chỗ được bọc: create tx2, `compensateToFailed`, `cancel`, và bước áp transition của webhook.

Sau khi đọc lại, kẻ thua thấy payment đã terminal → state machine từ chối → câu trả lời **nghiệp vụ**:

| Ai thua | Trả lời |
|---|---|
| cancel | `409 PAYMENT_ILLEGAL_TRANSITION` |
| webhook | `200`, event `REJECTED`, `rejectReason=ILLEGAL_TRANSITION` |

Thua cuộc đua là kết quả bình thường, không phải lỗi server.

**Chỉ retry xung đột khóa.** `IllegalPaymentTransitionException` là một *câu trả lời*, retry bao nhiêu lần cũng vậy.

## 4. Vì sao 3 lần là đủ

`version` chỉ đổi khi có status transition (các field provider được ghi chung với transition sang PENDING). Một payment có tối đa **hai** transition trong đời: `CREATED → PENDING → terminal`. Vậy một writer thua tối đa hai lần; lần thứ ba chắc chắn không còn ai tranh. Trong thực tế thường chỉ thua một lần.

Nếu vẫn hết lượt (không nên xảy ra): API trả `503 CONCURRENT_UPDATE`. 503 **không** nằm trong danh sách status được lưu của idempotency filter, nên key được nhả. Lưu ý: nếu chuyện này xảy ra ở create tx2 thì dòng CREATED đã commit sẽ chặn cùng `orderId` cho tới khi sweeper fail nó (2 phút).

Sweeper **không** retry: nó bắt lỗi từng dòng và lần quét sau sẽ xét lại.

Một hệ quả cần biết: nếu create bị treo hơn 2 phút giữa tx1 và tx2 (JVM stall, máy ngủ) và sweeper đã fail dòng CREATED, thì tx2 thua, đọc lại thấy FAILED và trả `409 PAYMENT_ILLEGAL_TRANSITION`. 409 **được lưu** bởi idempotency filter nên key đó replay 409 trong 24h, dù provider đã nhận phiên thanh toán. Trước phase này trường hợp đó là 500 (key được nhả). Provider chậm đơn thuần không gây ra được (timeout 2s + 5s); đây là việc của reconciliation ở increment 4.

## 5. Đo được

Mỗi lần **thử lại** tăng counter `fluxpay.payment.optimistic.retries` (tag `path` = `create|cancel|webhook|compensate`). Phase 4 dùng số này để so optimistic với pessimistic dưới tải.

## 6. Optimistic so với "last write wins"

| | Last write wins | Optimistic lock + retry |
|---|---|---|
| Lúc đọc | không khóa | không khóa |
| Xung đột | ghi đè lặng lẽ | phát hiện lúc ghi |
| Kẻ thua | không biết mình thua | đọc lại, nhận câu trả lời đúng |
| Chi phí | 0 | một lần làm lại khi có tranh chấp |

Hợp khi tranh chấp **hiếm** và transaction **ngắn** — đúng với payment (mỗi dòng chỉ vài writer trong cả đời). Phase 2 thêm cách còn lại: **pessimistic** (`SELECT ... FOR UPDATE`), xếp hàng writer ngay từ lúc đọc.

## Giới hạn đã biết

- Webhook hết lượt retry: ở phase này vẫn để event `RECEIVED` + 503. Phase 2 đổi thành `RETRYABLE` để provider gửi lại được.
- Postman không tạo được hai request đồng thời nên phase này không có folder Postman; `WebhookVsCancelRaceIT` là bản demo.

## Tự phá thử (bài tập)

1. Sửa `OptimisticRetry.MAX_ATTEMPTS` thành `1`, chạy `WebhookVsCancelRaceIT`: lỗi 5xx (giờ là `503 CONCURRENT_UPDATE`, không còn 500) và event kẹt quay lại. (`OptimisticRetryTest` cũng đỏ — vì sao?)
2. Trả lại `3`, rồi bỏ `@Version` trên `Payment.version`. Chạy `OptimisticLockConflictIT` và `WebhookVsCancelRaceIT`: không còn exception nào, nhưng đếm thử `payment_transitions` — có payment mang **hai** dòng terminal. Đó là lost update nguyên bản.
3. Trong `PaymentOrchestrator.cancel`, load payment **bên ngoài** `retry.execute` rồi dùng nó bên trong. Giải thích vì sao retry trở nên vô dụng.
