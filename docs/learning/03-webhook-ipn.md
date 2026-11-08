# 03 - Webhook / IPN và chống trùng

> Phase 4. Trái tim về mặt đúng đắn của gateway. Mục tiêu: hiểu vì sao webhook phải chịu được **trùng, trễ, giả mạo**, và vì sao mọi từ chối nghiệp vụ vẫn trả **200**.

## 0. Ghi chú failure demo

Plan gốc có ba demo phá (bật duplicates, gửi webhook giả, sửa amount). Ở đây tôi "đóng khung" cả ba bằng test trong `WebhookIngestionIT` (trùng → một dòng + duplicate_count=1; chữ ký sai → 401 không ghi; amount lệch → REJECTED) thay vì thao tác tay. Deviation ghi lại cho minh bạch; phần giải thích "nếu làm ngây thơ" ở dưới.

## 1. IPN là gì và vì sao "ít nhất một lần"

**IPN** (Instant Payment Notification) = webhook: provider chủ động gọi **server-to-server** báo kết quả. Đây là **nguồn sự thật**, khác đường redirect trình duyệt (chạy trên máy customer, không tin được).

Provider cam kết giao **ít nhất một lần** (at-least-once): có thể gửi **nhiều lần** cùng một sự kiện, hoặc **đến trễ**. Quy ước ngành: provider coi **mọi response ≠ 200 là "chưa nhận"** và **retry hàng giờ**. Hai hệ quả định hình toàn bộ thiết kế:

- Phải **chống trùng** (idempotent xử lý webhook).
- Phải **luôn trả 200** cho những thứ đã xử lý xong, kể cả khi từ chối vì nghiệp vụ.

## 2. Nếu làm ngây thơ (ba cái sai)

- **Trùng**: bật `mock.callback.duplicates=1`, webhook thứ 2 đập vào state machine của payment đã SUCCEEDED → ném lỗi → gateway trả 500/409 → provider tưởng fail, retry mãi.
- **Giả mạo**: gửi `status=SUCCESS` cho một giao dịch không có thật → payment SUCCEEDED dù **không ai trả tiền**.
- **Sửa tiền**: webhook hợp lệ nhưng `amount` bị sửa (1.000đ cho đơn 1.000.000đ) → SUCCEEDED với số tiền sai.

## 3. Bốn phòng thủ (thiết kế đã làm)

### 3.1 Chống trùng bằng ràng buộc DB, không exists-check
Mỗi webhook có `eventId`. Ta `INSERT ... ON CONFLICT (provider_code, event_id) DO NOTHING RETURNING id`. Không có id trả về → đã thấy rồi → chỉ `duplicate_count++`, trả 200 `{duplicate:true}`. **Ràng buộc unique là trọng tài duy nhất** — một exists-check-rồi-insert sẽ thua khi hai webhook trùng chạy song song. Kết quả: đúng **một dòng** `webhook_events`/sự kiện, đúng **một** transition.

### 3.2 Luôn 200 cho từ chối nghiệp vụ
Sau khi chữ ký hợp lệ, response **luôn 200** với thân `{received, duplicate, applied, rejectReason?}`. Từ chối nghiệp vụ (UNKNOWN_PAYMENT, REF_MISMATCH, AMOUNT_MISMATCH, ILLEGAL_TRANSITION) được **lưu** vào dòng event với `processing_status=REJECTED` + `reject_reason`, **không** trả 4xx/5xx. Chỉ trả lỗi HTTP cho sự cố hạ tầng, **trước khi ghi gì**: 401 (chữ ký sai), 404 (provider code lạ), 413 (body quá 64KB). Lý do: nếu trả 4xx cho lỗi nghiệp vụ, provider retry vô ích một thứ **không bao giờ** hợp lệ.

### 3.3 Chữ ký trên raw bytes
Header `X-Mock-Signature = lowercasehex(HMAC-SHA256(secret, rawBodyBytes))`. Gateway verify trên **đúng chuỗi byte thô** nhận được, nên controller đọc `byte[]`, **không** `@RequestBody DTO` — vì nếu để Spring parse JSON thành object rồi serialize lại, byte đổi và chữ ký vỡ. So sánh bằng `MessageDigest.isEqual` (constant-time), chống timing attack. `HmacSigner` (byte-based) ra đời ở đây, phase 5 tái dùng cho auth merchant.

### 3.4 Verify giá trị
Thứ tự trong tx xử lý: payment tồn tại (`findByProviderCodeAndProviderRef`, else UNKNOWN_PAYMENT) → `merchantRef` khớp payment.id (else REF_MISMATCH) → `amount` bằng (else AMOUNT_MISMATCH) → rồi mới `transition(SUCCEEDED|FAILED)`. Chặn đứng hai cái sai giả mạo ở mục 2.

## 4. Vì sao ba transaction, không một

`WebhookIngestionService` **không** `@Transactional`. Nó chạy **ba** `TransactionTemplate` tách biệt:

```
tx A : INSERT event ON CONFLICT DO NOTHING RETURNING id
        (không có id -> tx A': duplicate_count++, trả về {duplicate})
tx B : load payment, verify, transition(SUCCEEDED|FAILED)   // lỗi transition -> rollback CHỈ tx B
tx C : đánh dấu event APPLIED / REJECTED + reason
```

Lý do kỹ thuật (học riêng): một **unique violation làm abort cả transaction** Postgres; một **runtime exception băng qua `@Transactional`** đánh dấu transaction **rollback-only**. Nên kiểu "bắt lỗi rồi đi tiếp" trong **một** transaction sẽ commit được **gì cả** và trả 500. `IllegalPaymentTransitionException` được bắt **ngoài** tx B, nên webhook trễ trên payment đã EXPIRED bị từ chối (ILLEGAL_TRANSITION) mà **không** ghi đè trạng thái, và event vẫn được đánh dấu REJECTED ở tx C.

## 5. Một bẫy raw-JDBC đã gặp

Bảng `webhook_events` thao tác bằng `JdbcClient` (để dùng `ON CONFLICT ... RETURNING`). Khi bind thời gian, **không** bind thẳng `Instant`: driver PostgreSQL không suy ra được kiểu SQL cho `Instant` → 500. Phải đổi sang `OffsetDateTime` (UTC). (JPA ở các phase trước tự lo việc này nên ta không gặp.) `payment_id` nullable được bind dạng text rồi `cast(... as uuid)` để null không cần gợi ý kiểu.

## Tự phá thử (bài tập)

Đặt `mock.callback.delay-ms=70000` và `fluxpay.payment.expiry=1m`. Tạo payment, gọi "customer pays" trên mock-provider → nó trả 202 ngay. Quan sát: sweeper cho payment EXPIRED trước, rồi ~70s sau webhook SUCCESS đến trễ → bị **REJECTED (ILLEGAL_TRANSITION)**, trạng thái vẫn EXPIRED, event vẫn được lưu.

Câu hỏi: tiền đó giờ "lơ lửng" (customer đã trả nhưng đơn EXPIRED). Một gateway thật nên làm gì? (Trả lời ở increment 4: **reconciliation** — hỏi lại provider và hoàn tiền hoặc kích hoạt lại đơn theo chính sách.)
