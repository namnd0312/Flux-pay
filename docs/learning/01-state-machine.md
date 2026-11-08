# 01 - State machine của payment

> Phase 2. Mục tiêu: hiểu vì sao một payment phải là **máy trạng thái có luật**, không phải một ô `status` muốn gán gì thì gán.

## 0. Ghi chú về cách làm failure demo

Theo kế hoạch gốc, bước 1 là dựng bản "ngây thơ" (gán `setStatus` trực tiếp) rồi chạy để tận mắt thấy bug. Ở đây tôi làm gọn hơn đúng tinh thần dự án: **giải thích bug + chốt nó bằng test**, thay vì dựng một server vứt đi. Bug được "đóng khung" bằng `PaymentStateMachineTest` và `PaymentLifecycleIT` (hủy hai lần → 409). Đây là một deviation có chủ ý, ghi lại để minh bạch.

## 1. Bug nếu không có state machine

Hình dung service ngây thơ:

```java
payment.setStatus(SUCCEEDED); // bất kỳ ai, bất kỳ lúc nào
```

Kịch bản thật:
1. Payment hết hạn → sweeper đặt `EXPIRED`.
2. Một webhook SUCCESS **đến trễ** (khách bấm trả tiền phút chót).
3. Service ngây thơ gán `SUCCEEDED` đè lên `EXPIRED`.

Kết quả: một đơn đã "chết" bỗng sống lại thành công, **không có dấu vết** ai đổi, lúc nào, vì sao. Tiền và trạng thái đơn lệch nhau. Đây là loại bug đắt nhất của gateway: nó âm thầm, và chỉ lộ ra khi đối soát cuối ngày.

## 2. State machine là gì

Ba thành phần:
- **States (trạng thái):** tập hữu hạn. Ở đây: `CREATED, PENDING, SUCCEEDED, FAILED, EXPIRED, CANCELLED`.
- **Events/transitions (bước chuyển):** đi từ trạng thái này sang trạng thái kia, **chỉ những bước được phép**.
- **Transition table (bảng luật):** liệt kê rõ từ đâu được sang đâu. Không có trong bảng = cấm.

Bảng luật của Flux-pay:

| Từ | Được sang |
|---|---|
| CREATED | PENDING, FAILED |
| PENDING | SUCCEEDED, FAILED, EXPIRED, CANCELLED |
| SUCCEEDED / FAILED / EXPIRED / CANCELLED | (không đi đâu — terminal) |

Toàn bộ code chỉ có **một cửa** đổi trạng thái: `PaymentStateMachine.transition(payment, to, reasonCode, source)`. Nó (1) từ chối bước không có trong bảng bằng `IllegalPaymentTransitionException` (HTTP 409), và (2) ghi **một dòng audit** khi thành công. Nhờ "một cửa" này, không chỗ nào trong code gọi thẳng `payment.setStatus(...)` ngoài state machine — đó là một tiêu chí nghiệm thu được kiểm bằng `grep`.

## 3. Vì sao terminal phải là terminal

`SUCCEEDED, FAILED, EXPIRED, CANCELLED` ánh xạ sang tập rỗng. Nghĩa là: một payment đã kết thúc thì **không bước chuyển nào** còn hợp lệ. Chính luật này chặn đứng bug mục 1 — webhook trễ gặp `EXPIRED` sẽ bị từ chối, không ghi đè. (Ở phase 4, webhook trễ đó được lưu lại dưới dạng REJECTED thay vì vứt đi.)

## 4. Sổ cái audit để làm gì

Mỗi lần đổi trạng thái, ta ghi một dòng `payment_transitions`: từ đâu, sang đâu, `reason_code` (token ổn định như `MERCHANT_CANCEL`, `EXPIRED_BY_SWEEPER`, không phải free text), `source` (MERCHANT/PROVIDER/SYSTEM), thời điểm. Bảng này **chỉ thêm, không sửa/xóa**.

Vì sao quan trọng: khi đối soát (increment 4) hoặc khi khách khiếu nại "sao đơn tôi fail", ta trả lời được chính xác chuyện gì đã xảy ra theo trình tự thời gian. Trạng thái hiện tại là "ảnh chụp"; sổ cái là "lịch sử".

## 5. UUID và merchant scoping

- **id là UUID**, không phải số tự tăng: người ngoài không đoán được id kế tiếp để dò (no enumeration).
- **Mọi lượt đọc payment đi qua `findByIdAndMerchantId`**, không bao giờ `findById` trần. Merchant id là một phần của khóa tra cứu, nên merchant A không thể đọc payment của merchant B. Hỏi id người khác → **404** (không phải 403), để không lộ cả sự tồn tại của payment đó. Đây cũng là tiêu chí nghiệm thu kiểm bằng `grep` (không có `findById(` trong package `payment`).

## 6. Vài chi tiết kỹ thuật đã làm

- **Hết hạn:** một sweeper chạy mỗi 30s, chuyển PENDING quá hạn sang EXPIRED **qua state machine** (vẫn ghi audit). Mỗi dòng xử lý trong **transaction riêng** (`REQUIRES_NEW`) và bắt lỗi từng dòng, để một dòng hỏng không chặn cả mẻ. Sweeper **tắt** dưới profile `test`; test tự gọi `expireOverdue()` và dùng một `Clock` giả (`MutableClock`) để tua thời gian, không cần `sleep`.
- **Clock tiêm được:** đẩy payment quá hạn trong test chỉ là `clock.advance(16 phút)`.
- **`@Version`** đã có trên `payments` (optimistic lock) dù chưa dùng — để tránh đổi schema ở increment 2 khi xử lý race sweeper-vs-webhook.
- **Tạo payment (phase 2 tạm):** chưa có provider, nên CREATED → PENDING ngay với `payUrl = "pending-provider"`. Phase 3 thay bằng gọi provider thật, tách create thành hai transaction; các lệnh gọi state machine giữ nguyên.

## Tự phá thử (bài tập)

Thêm một trạng thái `REFUNDED` vào enum `PaymentStatus` **nhưng không** thêm vào bảng luật trong `PaymentStateMachine`. Chạy `PaymentStateMachineTest`. Quan sát: test parameterized sẽ sinh thêm các cặp `(REFUNDED, *)` và `(*, REFUNDED)` và so với bảng kỳ vọng trong test.

Câu hỏi tự trả lời:
1. Việc test **đỏ** ngay khi bạn thêm trạng thái mà quên khai luật — đó là điều **tốt** hay **xấu**? Vì sao?
2. Nếu đổi `PENDING` thành có thể quay lại `CREATED`, bước chuyển nào trong đời thực sẽ trở nên sai? (gợi ý: nghĩ tới webhook trùng và sweeper)
