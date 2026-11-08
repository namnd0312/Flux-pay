# 00 - Giải phẫu một payment gateway

> Note đầu tiên. Chưa có code chạy lỗi để "phá". Mục tiêu: hiểu **ai là ai**, **tiền và thông tin đi qua đâu**, và vì sao phần còn lại của dự án lại chia đúng thành 6 phase như vậy.

## 1. Các actor (nhân vật)

Một giao dịch trực tuyến luôn có 4 vai:

- **Customer** (người mua): bấm "Thanh toán" trên web/app của merchant.
- **Merchant** (người bán): shop online, họ có `api_key` + `secret` do gateway cấp. Merchant **không bao giờ tự giữ tiền hay thông tin thẻ** — họ đẩy việc đó cho gateway.
- **Gateway** (chính là Flux-pay): đứng giữa. Nhận yêu cầu tạo thanh toán từ merchant, chuyển hướng customer sang provider, rồi theo dõi kết quả và báo lại cho merchant.
- **Provider / Bank** (ví dụ VNPAY, MoMo, ngân hàng): nơi customer thực sự nhập OTP/quét QR và tiền thực sự chuyển. Trong dự án này, `mock-provider` (port 8081) đóng vai provider giả để ta chạy được toàn bộ luồng ở máy local.

Điểm mấu chốt: **gateway không phải là nơi trừ tiền**. Gateway là nơi *điều phối* và *ghi nhận* một giao dịch do provider thực hiện. Hiểu sai chỗ này sẽ dẫn tới thiết kế sai (ví dụ tưởng gateway "biết chắc" tiền đã vào).

## 2. Luồng redirect kiểu VNPAY

Luồng thanh toán phổ biến nhất của VNPAY là **redirect + IPN**:

1. Customer bấm thanh toán → merchant gọi gateway: *"tạo cho tôi một payment, số tiền X, mã đơn order_id Y"*.
2. Gateway tạo một bản ghi payment ở trạng thái **chờ** và trả về một **pay URL** (đường dẫn tới trang thanh toán của provider).
3. Customer được **chuyển hướng (redirect)** sang trang của provider, nhập OTP / quét QR.
4. Provider xử lý xong, báo kết quả về gateway bằng **IPN** (Instant Payment Notification) — một cuộc gọi **server-to-server**, chính là **webhook**.
5. Gateway cập nhật trạng thái payment theo IPN, rồi merchant đọc trạng thái đó để giao hàng.

Vì sao phải có bước 4 (webhook) mà không tin bước redirect quay về trình duyệt? Vì **redirect chạy trên máy customer** — customer có thể tắt tab, mất mạng, hoặc cố tình sửa URL. Chỉ có **IPN server-to-server** mới là nguồn sự thật đáng tin. Đây là lý do webhook (phase 4) là trái tim về mặt đúng đắn của gateway.

## 3. Gateway = một state machine + một sổ cái sự kiện

Tách gateway ra, nó chỉ gồm hai thứ:

- **State machine** (máy trạng thái): mỗi payment đi qua một tập trạng thái cố định (chờ → thành công / thất bại / hết hạn...). Các bước chuyển trạng thái **có luật**: ví dụ một payment đã **EXPIRED** thì một IPN báo **SUCCESS** đến trễ **không được** ghi đè. Luật này giữ cho tiền và đơn hàng không bị lệch.
- **Event ledger** (sổ cái sự kiện): mọi thứ provider gửi tới (mỗi IPN) được ghi lại thành một dòng, **không sửa, không xoá**. Nhờ đó ta trả lời được "chuyện gì đã xảy ra, lúc nào" khi cần đối soát, và phát hiện được webhook **trùng** (cùng một event id gửi 2 lần).

Nói ngắn gọn: **gateway đúng đắn = chuyển trạng thái có luật + ghi lại mọi sự kiện**. Gần như mọi bug nghiêm trọng của gateway đều là vi phạm một trong hai điều này.

## 4. Bản đồ 6 phase → trách nhiệm thật của gateway

| Phase | Làm gì | Ứng với trách nhiệm gateway nào |
|---|---|---|
| 1 (note này) | Bộ khung chạy được: 2 app, Postgres, Flyway, health, test | Hạ tầng để mọi thứ còn lại dựng lên |
| 2 | Payment + state machine + hết hạn | Chuyển trạng thái **có luật** |
| 3 | Provider interface + mock provider | Trừu tượng hoá "gọi ra ngoài tới bank" |
| 4 | Webhook (IPN) + chống trùng | Nguồn sự thật + sổ cái sự kiện |
| 5 | HMAC auth cho merchant | Chỉ merchant hợp lệ mới gọi được, request không bị sửa |
| 6 | Idempotency | Gọi lại cùng một request **không** tạo 2 giao dịch |

Phase 1 cố tình **không** dựng sẵn package `payment`, `webhook`... Ta chỉ tạo khung và bảng `merchants`. Mỗi khái niệm xuất hiện đúng lúc phase của nó bắt đầu, để không bị "ngợp cấu trúc" ngay từ đầu.

## 5. Khung ở phase 1 có gì

- `gateway` (8080): app chính, đã nối Postgres qua Flyway, có `/actuator/health`.
- `mock-provider` (8081): app rỗng, mới chỉ có health, sẽ thành provider giả ở phase 3-4.
- Bảng `merchants` + 1 merchant demo (`api_key = demo-merchant`). `api_key` chính là giá trị gửi trong header `X-Merchant-Id` từ phase 2 trở đi.
- Test dùng Testcontainers: bật một Postgres thật trong lúc chạy test, chạy đúng các migration Flyway → nhờ vậy `ddl-auto=validate` mới có ý nghĩa (nó bắt lỗi khi entity lệch khỏi schema).

## Tự phá thử (bài tập)

Tự tay vẽ lại **sequence diagram** cho một thanh toán **MoMo** từ trí nhớ, gồm đủ 4 actor (customer, merchant, gateway, provider) và **2 đường kết quả tách biệt**:

1. Đường **redirect** quay về trình duyệt customer.
2. Đường **IPN / webhook** server-to-server.

Sau đó tự trả lời: nếu customer tắt trình duyệt ngay sau khi trả tiền thành công ở MoMo, merchant có biết được đơn đã trả tiền không? Nhờ đường nào? Vì sao **không** được chỉ dựa vào đường redirect?
