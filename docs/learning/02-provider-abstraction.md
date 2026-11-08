# 02 - Trừu tượng hoá provider

> Phase 3. Mục tiêu: hiểu vì sao gateway tách khỏi provider cụ thể bằng ba mẫu thiết kế (Strategy, Adapter, Factory), và vì sao **gọi mạng phải nằm ngoài transaction DB**.

## 0. Ghi chú failure demo

Plan gốc bước 1 là viết bản ngây thơ (nhét lời gọi HTTP tới mock-provider thẳng vào `create()` có `@Transactional`), rồi tắt mock-provider để thấy request treo và giữ connection DB. Ở đây tôi "đóng khung" bài học bằng test thay cho bản vứt đi: `CountingFakeProvider` **khẳng định không có transaction nào đang mở** khi provider được gọi (nếu có, test đỏ ngay). Đó chính là bản demo "connection bị giữ" nhưng ở dạng kiểm chứng tự động. Deviation ghi lại cho minh bạch.

## 1. Vấn đề: nhiều provider, mỗi cái một kiểu

Mai thêm VNPAY, mốt thêm MoMo. Nếu `create()` gọi thẳng API của một provider, thì:
- Thêm provider = sửa `create()`, sửa DTO, sửa test — lan khắp nơi.
- Payment core "biết" chi tiết của provider → rối, khó test, dễ vỡ.

Giải pháp: core chỉ nói chuyện qua **một hợp đồng** `PaymentProvider`, không bao giờ biết class cụ thể. Một tiêu chí nghiệm thu kiểm điều này bằng `grep`: không có chữ "mock" trong package `payment`.

## 2. Ba mẫu thiết kế

- **Strategy** — `PaymentProvider` (interface): "cách tạo payment ở một provider". Mỗi provider là một chiến lược khác nhau, cùng một interface.
- **Adapter** — `MockProviderAdapter`: **dịch** API riêng của provider (HTTP, field tên gì, trả gì) sang hợp đồng chung `ProviderPaymentRequest/Result`. VNPAY sau này chỉ là một adapter nữa.
- **Factory/Registry** — `ProviderRegistry`: gom tất cả provider bean, cho `get(code)` để chọn theo mã. Hai provider trùng mã → **fail ngay lúc khởi động** (lỗi cấu hình phải lộ ngay, không đợi tới request). Mã mặc định lấy từ **config**, nên core không hard-code tên provider nào.

Thêm provider mới = **một class + một dòng config**. Không đụng payment core.

## 3. Vì sao gọi mạng phải ngoài transaction DB

Đây là bài học đắt nhất của phase này. Nếu gọi HTTP provider **bên trong** một DB transaction:
- Transaction giữ một **connection từ pool** suốt thời gian chờ HTTP.
- Provider chậm/treo (ví dụ 5s timeout) → connection bị giữ 5s. Nhiều request cùng lúc → **cạn pool**, cả hệ thống đứng. Đây là nút thắt increment 2 sẽ đo.

Nên `create()` chia làm hai transaction, gọi provider **ở giữa, không có transaction nào mở**:

```
tx1: INSERT payment = CREATED            (commit, nhả connection)
     provider.createPayment(...)         (gọi HTTP, KHÔNG giữ connection DB)
tx2: set providerRef/payUrl, -> PENDING  (commit)
```

### Bẫy `@Transactional` tự gọi (self-invocation)

Vì sao không dùng `@Transactional` mà dùng `TransactionTemplate`? Vì `@Transactional` hoạt động qua **proxy**: nếu một method trong cùng bean gọi method `@Transactional` khác của chính nó, lời gọi **không đi qua proxy** → annotation bị bỏ qua, hai bước gộp thành một transaction mà không ai nhận ra. `TransactionTemplate` làm ranh giới transaction **hiện và rõ ràng**, không phụ thuộc proxy. `CountingFakeProvider` khẳng định đúng điều này: lúc nó chạy, `isActualTransactionActive()` phải là `false`.

## 4. Bù trừ cho CREATED mồ côi

Chia hai transaction mở ra một khe hở: nếu provider lỗi, hoặc JVM chết **giữa** tx1 và tx2, ta còn một dòng `CREATED` không bao giờ thành PENDING.

Xử lý:
- **Provider lỗi** (`ProviderException`): bù trừ ngay — `CREATED -> FAILED` lý do `PROVIDER_UNAVAILABLE`, trả **502**. Thân response **không** chứa chi tiết lỗi gốc (chỉ log mới có), tránh lộ thông tin.
- **Lỗi khác**: `CREATED -> FAILED` lý do `INTERNAL_ERROR`, trả 500.
- **JVM chết giữa chừng**: không ai bù trừ được → sweeper quét các dòng `CREATED` quá cũ (quá `created-timeout`, mặc định 2m) và fail chúng với lý do `CREATE_ABANDONED`. (Increment 4 sẽ thay bằng hỏi lại provider xem giao dịch có thật không — reconciliation.)

## 5. Luồng redirect với payUrl + providerRef

```
Merchant --POST /api/v1/payments--> Gateway
Gateway  --tx1--> CREATED
Gateway  --createPayment()--> Provider(mock-provider :8081)
Provider --{providerRef, payUrl}--> Gateway
Gateway  --tx2--> PENDING (lưu providerRef + payUrl)
Gateway  --201 {payUrl}--> Merchant
Merchant --redirect--> Customer mở payUrl tại Provider
```

- **providerRef**: mã của provider cho giao dịch này. Gateway lưu lại để sau đối chiếu webhook (phase 4) về đúng payment.
- **payUrl**: nơi gửi customer tới để trả tiền. Mock-provider trả dạng `http://localhost:8081/mock/v1/pay/{providerRef}`; hành động "customer trả tiền" ở payUrl này được code ở phase 4.

## Tự phá thử (bài tập)

1. Viết `FakeBankAdapter implements PaymentProvider` với `code() = "FAKEBANK"`, `createPayment` luôn ném `ProviderException`. Đăng ký nó (chỉ cần là một bean). Tạo payment với `provider=FAKEBANK`. Xác nhận: **không file nào trong `payment/` phải sửa** — đó là điểm của trừu tượng hoá.
2. Thử dời hai lời gọi `TransactionTemplate` trong `create()` vào **một** method `@Transactional` duy nhất, chạy `PaymentCreateWithProviderIT`. Quan sát khẳng định "không có transaction khi gọi provider" **đỏ**. Giải thích vì sao đỏ là đúng.
