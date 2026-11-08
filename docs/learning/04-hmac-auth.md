# 04 - Ký request bằng HMAC (merchant auth)

> Phase 5. Mục tiêu: hiểu vì sao "biết api key" **không** đủ để chứng minh danh tính, HMAC giải quyết chuyện đó thế nào, và vì sao timestamp + method + path + body phải nằm **trong** phần được ký.

## 0. Ghi chú failure demo

Từ phase 2 đến phase 4, merchant chỉ gửi header `X-Merchant-Id: demo-merchant`. Gateway tra key đó ra UUID rồi tin luôn. Hai demo phá dưới đây "đóng khung" bằng test `MerchantAuthFilterIT` thay vì thao tác tay; phần "làm ngây thơ" giải thích vì sao chúng chạy được trước phase 5.

**Demo 1 — ai biết api key cũng giả mạo được.** api key không phải mật khẩu: nó đi kèm **mọi** request, nằm trong log, trong lịch sử proxy, trong màn hình Postman. Nếu api key vừa là *danh tính* vừa là *bằng chứng sở hữu*, thì bất kỳ ai nhìn thấy nó một lần đều hành động được dưới danh nghĩa merchant đó — tạo payment, hủy payment. Trước phase 5 không có gì để phân biệt "chính chủ" với "kẻ copy được api key".

**Demo 2 — replay.** Chụp lại một request hợp lệ (cùng header, cùng body), gửi lại một ngày sau → vẫn được chấp nhận, vì không có gì ràng buộc request vào **thời điểm** nó được tạo.

## 1. Vấn đề: định danh ≠ xác thực

- **Định danh (identification):** "Tôi là merchant A." → api key làm được.
- **Xác thực (authentication):** "Tôi **chứng minh** tôi là merchant A." → cần một **bí mật** mà chỉ A và gateway biết, và A phải chứng minh mình giữ bí mật đó **mà không gửi nó đi**.

Giải pháp ngành (VNPAY `vnp_SecureHash`, MoMo `signature`): mỗi merchant có một **shared secret**. Request được **ký** bằng secret đó; gateway tự ký lại và so sánh. Secret **không bao giờ** lên đường truyền — chỉ chữ ký đi.

## 2. HMAC là gì

**HMAC-SHA256(secret, data)** = một hàm băm có khóa: ai không có `secret` thì không tạo được chữ ký đúng cho `data`, và cũng không suy ngược ra `secret` từ chữ ký. Khác hash trần (SHA-256 thường) ở chỗ có khóa — nên kẻ tấn công sửa `data` rồi hash lại là vô dụng, vì chúng không có khóa để tạo chữ ký khớp.

`X-Signature = lowercasehex(HMAC-SHA256(secretBytes, canonicalBytes))`. So sánh bằng `MessageDigest.isEqual` (constant-time) để không rò rỉ thông tin qua thời gian so sánh.

## 3. Ký cái gì: "canonical string"

Ký mỗi body là **chưa đủ**. Phải ký một chuỗi gói đủ ngữ cảnh (`CanonicalRequest`, UTF-8, ngăn bằng `\n`):

```
METHOD            POST
PATH              /api/v1/payments        (không host, không query)
X-Timestamp       1762500000000           (epoch millis)
Idempotency-Key   giá trị header, hoặc dòng trống nếu không có
sha256hex(body)   hex thường của SHA-256 body thô; sha256 của rỗng cho request không body
```

Vì sao từng thành phần:

- **METHOD + PATH**: chặn **cross-endpoint replay**. Nếu chỉ ký body, chữ ký của một `GET /payments/{id}` (body rỗng) **dùng lại được** cho `POST /payments/{id}/cancel` (cũng body rỗng) → ký một thao tác đọc lại hủy được đơn. Đưa method+path vào, chữ ký của GET không khớp canonical của POST-cancel.
- **sha256(body)**: chặn **sửa body sau khi ký**. Đổi một ký tự trong body → hash đổi → chữ ký vỡ.
- **X-Timestamp nằm *trong* phần ký**: để timestamp không bị sửa. Gateway chỉ chấp nhận khi `|now - ts| ≤ 5 phút`. Đây là **chặn trên tuổi thọ** của một request bị chụp lại (bounds replay lifetime), **không** phải chống trùng tuyệt đối — trong 5 phút đó replay vẫn lọt. Thứ khử tác dụng trùng lặp hoàn toàn là **Idempotency-Key** đã được ký (phase 6): gửi lại y hệt chỉ nhận lại đúng một kết quả, không tạo đơn mới.
- **Idempotency-Key trong canonical**: để khóa chống-trùng ở phase 6 cũng được ký, kẻ tấn công không tráo được key.

Lưu ý charset: mọi dòng đều ASCII **trừ** hash cuối; body vào qua hex digest, nên gateway (Java) và Postman (CryptoJS) tạo **cùng** chuỗi byte dù xử lý Unicode khác nhau.

## 4. Thứ tự kiểm tra trong filter (fail closed)

`MerchantAuthFilter` là `OncePerRequestFilter` trên `/api/*` (webhook dùng chữ ký provider của phase 4; `/actuator/health` công khai). Chạy **trước** Spring MVC, nên nó đọc body thô một lần rồi bọc request lại (`CachedBodyHttpServletRequest`) để controller vẫn `@RequestBody` được.

```
1. cap body 64KB           -> 413 REQUEST_BODY_TOO_LARGE (trước khi buffer hết)
2. đủ 3 header auth?        -> 401 AUTH_MISSING_HEADERS
3. tra merchant theo key    -> 401 AUTH_UNKNOWN_MERCHANT
4. merchant active?         -> 401 AUTH_MERCHANT_INACTIVE
5. |now - ts| <= 5m?        -> 401 AUTH_STALE_TIMESTAMP
6. giải mã secret           -> 401 AUTH_BAD_SIGNATURE (log ERROR, KHÔNG phải 500)
7. verify chữ ký (hằng thời) -> 401 AUTH_BAD_SIGNATURE
   -> đặt MerchantContext lên request, cho đi tiếp
```

Mọi từ chối là **401** (hoặc 413), viết bằng RFC 7807 qua `ProblemDetailWriter` dùng chung với controller advice — filter chạy ngoài MVC nên không trả được `ProblemDetail` object như controller, phải tự serialize. **Không đường nào ra 500**, kể cả khi secret trong DB không giải mã được (đó là lỗi vận hành của *gateway*, log ERROR cho operator thấy, nhưng với merchant chỉ là 401).

> **Vì sao giữ nhiều mã 401 khác nhau?** Chỉ để **học** cho dễ soi. Production thường gộp hết thành một `401 unauthorized` chung, vì phân biệt "unknown merchant" với "bad signature" giúp kẻ tấn công **dò** api key nào có thật (enumeration). Đây là một đánh đổi học-tập-vs-bảo-mật, ghi rõ để nhớ.

## 5. Secret mã hóa khi lưu (encryption at rest)

Cột `merchants.secret` không còn là plaintext mà là `v1:` + base64(iv‖ciphertext‖tag), **AES-256-GCM** (`SecretCipher`):

- **IV ngẫu nhiên 12 byte mỗi lần mã hóa** (`SecureRandom`): hai lần mã hóa cùng một plaintext ra khác nhau → không rò rỉ "hai merchant có cùng secret".
- **GCM có xác thực**: tag phát hiện sửa đổi; **AAD = api key** nên ciphertext copy sang dòng merchant khác sẽ **không giải mã được** (thay vì âm thầm giải ra secret sai).
- **Fail-fast**: `SecretCipher` ném lỗi ngay khi khởi tạo nếu `FLUXPAY_SECRET_KEY` thiếu hoặc không đúng 32 byte; `SecretCipherStartupCheck` còn giải thử secret của DEMO lúc boot — sai khóa thì **app không khởi động**, thay vì boot xong rồi mọi request thành `AUTH_BAD_SIGNATURE` (trông như lỗi client).

Vì Flyway chạy SQL thuần (migration Java không inject được bean Spring), ciphertext cho merchant DEMO được **tính sẵn một lần** bằng `EncryptSecretTool` với **dev key** rồi commit vào `V5`. Dev key nằm ở `.env.example` và `application-test.yml`, ghi rõ "chỉ dev".

**Vì sao đây *chưa* phải production grade:** khóa là một khóa đối xứng đọc từ env, không phải khóa trong **KMS/HSM** có **xoay vòng (rotation)** và phân quyền. Thật ra phải: khóa ở KMS, không bao giờ nằm trong repo; có quy trình rotation; có versioning (`v1:`, `v2:` đã chừa sẵn prefix cho việc này). Xoay khóa ở đây = tạo khóa mới (`openssl rand -base64 32`), chạy lại `EncryptSecretTool`, thêm migration mới.

## 6. Clock skew

Cửa sổ 5 phút cũng để chịu **lệch đồng hồ** giữa máy merchant và gateway. Production nên chạy **NTP** cả hai phía để lệch nhỏ hơn nhiều so với cửa sổ.

## Tự phá thử (bài tập)

1. Trong Postman, ký một request create rồi **sửa một ký tự trong body** trước khi gửi (ví dụ đổi `amount`). Quan sát: `401 AUTH_BAD_SIGNATURE`, vì hash body trong canonical không còn khớp.
2. Trên một nhánh nháp, bỏ dòng `PATH` khỏi `CanonicalRequest.build` (và khỏi script Postman cho khớp). Ký một `GET /payments/{id}`, rồi dùng đúng `X-Timestamp` + `X-Signature` đó gửi `POST /payments/{id}/cancel`. Quan sát: **lọt** → hủy được đơn bằng chữ ký của một thao tác đọc. Khôi phục `PATH`, chạy lại → `401`. Đây đúng là lỗ hổng mục 3 nói tới.

Câu hỏi: timestamp window chặn replay "một ngày sau", nhưng **trong** 5 phút thì replay vẫn lọt và tạo đơn trùng. Cái gì khử hẳn tác dụng trùng? (Trả lời ở phase 6: **idempotency key** đã được ký — gửi lại chỉ nhận lại đúng một kết quả.)
