# 09 - Load test: chứng minh bằng số, và nút thắt đầu tiên

> Increment 2, phase 4. Mục tiêu: dựng một bộ load test chạy lại được, **đo** xem các sửa chữa ở phase 1-3 có đứng vững dưới tải thật qua HTTP không, so optimistic với pessimistic bằng số, và tìm **nút thắt đầu tiên** bằng metric chứ không đoán.

Số đo có hai đợt: **2026-11-13** (build increment 1 và so sánh optimistic/pessimistic) và **2026-11-15** (build cuối, sau khi sửa harness — mục 1). Tất cả chạy trên **một laptop** chạy cùng lúc JMeter, gateway, mock-provider và Postgres (docker compose). Vì vậy **số tuyệt đối không phải năng lực hệ thống**; chỉ nên đọc *so sánh tương đối* và *hình dạng bão hòa*. Mỗi cấu hình chạy **một lần** (kế hoạch định chạy 3 lần lấy trung vị; chưa làm).

## 0. Bộ công cụ

```
load-tests/
  run.sh                         chạy 1 kịch bản headless → chờ hệ thống lắng → kiểm bất biến
  hmac-sign.groovy               ký request merchant (JSR223 PreProcessor)
  baseline-create-pay-poll.jmx   tạo → trả tiền ở mock → poll tới khi terminal
  same-key-burst.jmx             N thread, cùng key + body, cùng lúc
  webhook-vs-cancel.jmx          tạo → trả tiền → chờ ≈ độ trễ webhook → cancel
  verify-invariants.sql          các bất biến phải = 0
  collect-metrics.sh             lấy mẫu metric mỗi 2s
  docker-compose.loadtest.yml    override: mở metrics ở 127.0.0.1:9090 / 9091
```

Chạy: `docker compose -f docker-compose.yml -f load-tests/docker-compose.loadtest.yml up -d --build`, rồi `METRICS=1 load-tests/run.sh webhook-vs-cancel -Jthreads=50 -Jloops=20`.

Hai bẫy khi ký request trong load tool (đã gặp, xem `hmac-sign.groovy`):

1. **Byte được hash phải là byte được gửi.** Nếu viết `${__UUID}` thẳng trong body, JMeter tính lại nó lúc gửi → khác với lúc ký → 401. Phải sinh `orderId` vào biến **một lần** rồi dùng biến.
2. Path được ký **không kèm query string**.

Một JMeter thread chạy các sampler **tuần tự**, và Synchronizing Timer gom **thread** chứ không gom sampler. Nên kịch bản đua là theo *thời gian*: mock gửi webhook sau `MOCK_CALLBACK_DELAY_MS=100`, còn JMeter chờ 60-140 ms (`-JcancelAfterMs=60 -JcancelJitterMs=80`) rồi cancel.

Lệnh chính xác của từng bảng nằm trong `load-tests/results/<thời điểm>-<kịch bản>-<nhãn>/` (thư mục bị git-ignore, chỉ có trên máy đã chạy); tham số được ghi ngay dưới mỗi bảng.

## 1. Bất biến: kiểm cái gì, lúc nào

`verify-invariants.sql` chạy **sau khi hệ thống đã lắng** (20s, dài hơn cửa sổ retry 15.5s của mock). Các dòng `HARD` phải bằng 0:

| Bất biến | Ý nghĩa nếu ≠ 0 |
|---|---|
| payment có > 1 transition terminal | hai writer cùng "thắng" |
| `payments.status` ≠ transition mới nhất | ledger và payment kể hai chuyện khác nhau |
| event còn `RECEIVED` | một lần giao không ghi lại kết quả |
| event `SUCCESS` không `APPLIED` mà payment không `SUCCEEDED`/`CANCELLED` | **mất tiền** |
| payment `PENDING` mà đã có event | webhook tới nhưng payment không nhúc nhích |
| event còn `RETRYABLE` | provider đã bỏ cuộc, hoặc kiểm quá sớm (chưa hết cửa sổ retry) |
| > 1 payment **còn sống** (`CREATED`/`PENDING`/`SUCCEEDED`) cho một order | charge trùng |
| idempotency key còn `IN_PROGRESS` | lease bị bỏ rơi |

Ba bài học khi viết chúng:

- **Không dùng bộ lọc "cũ hơn N phút".** Ngay sau một lượt chạy ngắn, bộ lọc đó không khớp dòng nào và check "xanh" mà chẳng kiểm gì.
- **Một check sai là một check ồn.** Bản đầu coi mọi payment `PENDING` là lỗi; lượt baseline báo 74 "vi phạm". Kiểm lại: cả 74 đều **không có event nào** — lượt chạy theo thời gian cắt thread giữa "tạo" và "trả tiền". Check được viết lại thành "PENDING **mà đã có event**".
- **Bất biến xanh chưa chắc là lượt chạy tốt.** Bản đầu của `run.sh` chỉ đọc SQL. Nếu mọi request trả 401 (đồng hồ VM lệch sau khi máy ngủ) hoặc gateway không chạy, database trống → mọi check bằng 0 → "xanh". JMeter cũng thoát với mã 0 dù mọi sample hỏng. Nay `run.sh` chỉ báo `PASSED` khi **cả bốn** điều cùng đúng: không sample nào hỏng assertion, lượt chạy **có tạo** payment, đủ số dòng `HARD` và tất cả bằng 0, và (với `METRICS=1`) mock không làm rơi webhook nào. Đối chứng: chạy với `-Jport=1` phải ra `FAILED` (mục 5).

Một lượt chạy chỉ hợp lệ khi mock không tự làm rơi webhook: `mock.webhook.rejected = 0` và `mock.webhook.exhausted = 0`. Ở mọi lượt trên build mới, cả hai đều bằng 0.

## 2. Trước và sau: cuộc đua webhook-vs-cancel

3 thread × 300 vòng = **900 cuộc đua**, `MOCK_CALLBACK_DELAY_MS=100`, `-Jthreads=3 -Jloops=300 -JcancelAfterMs=60 -JcancelJitterMs=80`.

| | Increment 1 (11-13) | Increment 2, optimistic (11-13) | Increment 2, build cuối (11-15) |
|---|---|---|---|
| cancel trả 500 | **97** | 0 | 0 |
| event kẹt `RECEIVED` | **143** | 0 | 0 |
| bất biến HARD | **vỡ** (143) | giữ | giữ (8/8) |
| webhook thắng / cancel thắng | 388 / 512 | 405 / 495 | 465 / 435 |
| retry do thua version | — | 279 | 36 |
| trong đó trên đường webhook | — | có | **0** |
| p95 cancel | — | 13 ms | 11 ms |

Đây là chỉ số thành công của roadmap: xung đột gây lỗi **> 0 trước, = 0 sau**. Lưu ý ở build cũ JMeter chỉ thấy 97 lỗi (phía cancel); 143 webhook lỗi nằm ở phía mock, và chỉ lộ ra nhờ câu SQL bất biến.

(Đợt 11-13 có hai lượt 3 thread — optimistic và pessimistic — cùng ra đúng 405 / 495. Trông như chép nhầm nên đã kiểm lại cả hai file `.jtl`: đúng là 405 cancel-409 và 495 cancel-200 ở cả hai. Với 3 thread và cùng tham số, thời điểm gần như lặp lại.)

**Lần thử "trước" đầu tiên thất bại vì chính mock.** Với 50 thread, mock cũ (pool gửi webhook 2-4 thread, hàng đợi 50) trả **500 cho 748/1000** lần trả tiền; 252 webhook còn lại tới trễ sau cancel, không có cuộc đua nào. Phải hạ xuống 3 thread thì mock cũ mới theo kịp. Bài học: **loại trừ test double trước khi kết luận về hệ thống được test.** Mock mới (pool cấu hình được, đếm số bị rơi) không gặp chuyện này ở 50 hay 400 thread.

## 3. Optimistic hay pessimistic? (đã quyết: bỏ optimistic)

Đợt 11-13, 50 thread × 20 vòng = 1000 cuộc đua, database mới cho mỗi lượt:

| | Optimistic | Pessimistic |
|---|---|---|
| lỗi / bất biến vỡ | 0 / 0 | 0 / 0 |
| retry tổng | 204 | 136 |
| retry trên đường webhook | 120 (*) | **0** (*) |
| p95 cancel | 118 ms | 123 ms |
| p95 create | 273 ms | 246 ms |
| Hikari pending (max) | 27 | 21 |

(*) Không truy được về file kết quả nào: `metrics.csv` đợt 11-13 chỉ lưu **tổng** retry (204 / 136), chưa có cột theo đường. Con số 0 cho pessimistic thì được xác nhận độc lập: `WebhookVsCancelRaceIT` assert nó, và build cuối (cột theo đường đã có) đo 0 ở mọi lượt. Con số 120 nên coi là chưa kiểm chứng.

Đọc số:

- **Cả hai đều đúng.** Không có khác biệt nào về tính đúng đắn.
- Pessimistic **xóa hẳn** retry trên đường webhook (webhook giữ row lock từ lúc đọc tới lúc commit nên không thể thua version check). Retry còn lại là của cancel, vì cancel không lấy khóa.
- **Latency không phân biệt được** ở mức tải này: chênh vài ms, một lượt chạy, trên laptop — nằm trong nhiễu.
- Không quan sát được lock wait: lấy mẫu `pg_stat_activity` mỗi 2s quá thưa so với khóa giữ vài ms. Cột "lock waiters" trong CSV gần như luôn 0; **đừng** đọc đó là "không có chờ khóa".

Số liệu không chọn ra bên thắng về hiệu năng; pessimistic cho hành vi dễ đoán hơn trên đường webhook. **Quyết định (2026-11-15): bỏ nhánh optimistic** cùng công tắc `fluxpay.webhook.lock-mode`. Bỏ gần như không tốn gì: `OptimisticRetry` vẫn cần cho cancel/create/compensate.

Build cuối, cùng 1000 cuộc đua (`-Jthreads=50 -Jloops=20`, 11-15): 0 lỗi, bất biến giữ, webhook thắng 458 / cancel thắng 542, retry 70 — **tất cả** trên đường cancel, 0 trên webhook. p95 cancel 55 ms, p95 create 108 ms, Hikari pending tối đa 12. Ở 50 thread tỉ lệ nghiêng nhẹ về cancel so với 3 thread. Một giải thích khả dĩ (chưa đo): dưới tải, webhook tới gateway muộn hơn độ trễ danh nghĩa 100 ms.

## 4. Nút thắt đầu tiên

### 4.1 Provider nhanh

`baseline-create-pay-poll`, `-Jduration=30`, ramp-up 5s, JVM đã warm-up, database xóa trước mỗi lượt (11-15):

| Thread | Payment / s | Request HTTP / s | p95 create | p50 poll | Hikari active / max | Hikari pending | Tomcat busy / max |
|---|---|---|---|---|---|---|---|
| 10 | 78 | 311 | 19 ms | 2 ms | 2 / 10 | 0 | 7 / 200 |
| 50 | 307 | 1226 | 82 ms | 6 ms | **10 / 10** | 28 | 36 / 200 |
| 200 | 351 | 1758 | 300 ms | 71 ms | **10 / 10** | **160** | 170 / 200 |

Đọc cho đúng:

- **Hai dòng đầu đo JMeter, không đo hệ thống.** Workload đóng: mỗi vòng = create + pay + ít nhất một poll với timer 100 ms. 10 thread ÷ ~0.13 s ≈ 77 vòng/s; 50 ÷ ~0.165 s ≈ 300. Đúng bằng số đo: đó là tải *được đưa vào*, chưa phải giới hạn.
- **Chỉ bước 50 → 200 là có tranh chấp.** Tải đưa vào gấp 4, throughput chỉ +14% (307 → 351/s), còn p95 create ×3.7 và poll ×12. Hàng đợi hiện ra ở **connection pool**: 10/10 active, 160 request chờ connection. Tomcat 170/200 là hệ quả: thread đứng chờ connection.
- **Không lặp lại được "throughput giảm".** Đợt 11-13 đo 300 → 254/s ở cùng bước này; đợt 11-15 ra 307 → 351/s. Mỗi đợt một lượt chạy: chênh lệch đó nằm trong nhiễu. Kết luận vững là "phẳng ra", không phải "giảm".
- **Pool là nơi hàng đợi hình thành, chưa chứng minh là nguyên nhân.** Muốn chứng minh phải tăng pool và thấy throughput tăng (bài tập 2; ngoài phạm vi phase này). Một vòng không chỉ có create: create mượn connection ~5 lần (xác thực merchant, `begin` idempotency, tx1, tx2, `complete`), mỗi poll 3 lần (xác thực, đọc payment, đọc transition), mỗi webhook ≥ 4 lần. Tổng ≥ 12 lượt mượn mỗi vòng; create chưa tới một nửa.

**Giả thuyết ban đầu sai với provider nhanh.** Kế hoạch đoán "request thread bị chặn ở lời gọi provider, Hikari gần như rảnh". Thực tế ngược lại: Hikari đầy trước, Tomcat còn dư.

### 4.2 Provider chậm

Cùng kịch bản, `MOCK_CREATE_DELAY_MS` đặt cho mock (11-15):

| Độ trễ provider / thread | Payment / s | p50 create | p95 create | p50 poll | Hikari pending | Tomcat busy (gateway) | Tomcat busy (mock) |
|---|---|---|---|---|---|---|---|
| 500 ms / 200 | 221 | 602 ms | 896 ms | 52 ms | 103 | 200 / 200 | 173 |
| 2000 ms / 200 | 91 | 2021 ms | 2084 ms | 3 ms | 0 | 193 / 200 | 192 |
| 2000 ms / 400 | 95 | 2208 ms | 2656 ms | **400 ms** | 4 | **200 / 200** | 200 |

Dòng 200 thread **một mình chưa chứng minh gì**: 200 client trong workload đóng thì Tomcat ~200 busy là đương nhiên, và p50 create 2021 ms chỉ là độ trễ provider, không có xếp hàng. Lượt **400 thread** mới là phép thử:

- Gấp đôi client, throughput gần như không đổi (91 → 95/s).
- p95 create **vượt** độ trễ provider (2656 ms so với 2000 ms): request phải chờ trước khi được phục vụ.
- Bằng chứng rõ nhất là **poll**: một lần đọc database, không gọi provider. p50 poll từ 3 ms lên **400 ms** trong khi Hikari pending chỉ 4 (pool có lúc đủ 10/10 nhưng gần như không ai phải xếp hàng chờ connection). Không phải chờ connection mà poll vẫn mất 400 ms → nó chờ **request thread**.

Vậy với provider chậm, **request thread Tomcat bị giữ suốt lời gọi provider đồng bộ** là nút thắt; đây đúng là kịch bản giả thuyết ban đầu mô tả.

Caveat: Tomcat của **mock** cũng 200/200 ở lượt này. Mỗi thread gateway đang gọi create giữ một thread mock suốt 2 s, và lệnh "trả tiền" của JMeter cũng cần thread mock. Hai pool cùng cỡ 200 nối tiếp nhau; tách hẳn hai bên cần một lượt với nhiều thread mock hơn (chưa làm). Điều poll chứng minh thì không phụ thuộc mock: poll không đi qua mock. Và lệnh trả tiền gọi thẳng mock vẫn có p95 4 ms ở lượt này, nên mock không phải chỗ request xếp hàng. Mock không làm rơi webhook nào (`rejected = exhausted = 0`).

Tóm lại:

1. **Provider nhanh:** hàng đợi hình thành đầu tiên ở **connection pool** (10). Là nguyên nhân hay không: chưa xác nhận.
2. **Provider chậm:** nút thắt là **request thread bị giữ** suốt lời gọi provider đồng bộ (chứng minh bằng lượt 400 thread).

Caveat chung: workload **đóng** (mỗi thread JMeter chờ response rồi mới gửi tiếp), nên throughput bị chặn bởi số thread ÷ latency. Tải thật là workload **mở** (request cứ tới bất kể hệ thống chậm), khi đó hàng đợi dài ra thay vì throughput tự hạ.

## 5. Các kiểm tra khác

- **Burst cùng key** (20 thread × 20 key, 11-15): 20 payment cho 20 order; 20 × `201` mới, 380 × `409 IDEMPOTENCY_IN_PROGRESS`. Giờ **kiểm bằng assertion** chứ không chỉ đếm mã: một `201` không có header `Idempotency-Replayed` chỉ được xuất hiện một lần mỗi key, mọi `409` khác phải mang `IDEMPOTENCY_IN_PROGRESS`. Lý do: bản cũ chấp nhận mọi `201|409`, mà unique index của order (V7) một mình cũng giữ được "1 payment mỗi order" — bỏ hẳn idempotency filter thì kịch bản vẫn xanh. Đối chứng: cho mỗi thread một key riêng (giả lập idempotency hỏng) → 57/60 request bị đánh dấu hỏng với `409 ORDER_ALREADY_EXISTS`. (Lượt burst đầu tiên của đợt 11-15 báo 380 sample hỏng: lỗi của chính harness — assertion mới thay hẳn assertion mã trạng thái, nên JMeter vẫn coi mọi 4xx là hỏng. Sửa bằng cách giữ cả hai, chạy lại.)
- **Đối chứng harness**: `run.sh baseline-create-pay-poll -Jport=1` → `FAILED: 5699 JMeter samples failed`, `FAILED: the run created no payment`. Bản cũ báo "All HARD invariants hold" cho đúng lượt này.
- **Webhook sớm, end-to-end với mock thật** (11-13, `MOCK_CALLBACK_AUTO_PAY=SUCCESS`, `MOCK_CREATE_DELAY_MS=500`): 16 payment, cả 16 webhook đầu bị trả lời "quay lại sau" (mock log `attempt 1/6 -> RETRY`), lần hai `DELIVERED`, cả 16 payment `SUCCEEDED`, bất biến giữ. Đây là bản chạy thật của kịch bản ở note 07 mà phase 2 chưa làm được.

## 6. Cái chưa đo

- Mỗi cấu hình chỉ chạy một lần; chưa có trung vị, chưa có khoảng tin cậy (mục 4.1 cho thấy vì sao điều này quan trọng).
- Chưa thử đổi cỡ pool để xác nhận pool là nguyên nhân (ngoài phạm vi: phase này đo, không tune).
- Chưa tách thread mock khỏi thread gateway ở lượt provider chậm.
- Chưa đo workload mở, chưa đo lâu (30 giây không lộ ra rò rỉ hay GC).
- Lock wait chưa quan sát được (mục 3).
- JMeter chạy cùng máy nên tự nó cũng ăn CPU của hệ thống được test.

## 7. Gợi ý cho increment 3

- Số lượt mượn connection mỗi vòng (≥ 12; create ~5) là đòn bẩy rõ nhất trước khi nghĩ tới việc tăng pool.
- Lời gọi provider đồng bộ giữ request thread: đây là lý do có thật để đưa bước đó ra bất đồng bộ (Kafka), nhưng **chỉ** trở thành nút thắt khi provider chậm.
- Event `RETRYABLE` không được gửi lại và event kẹt `RECEIVED` cần một inbox consumer.

## Tự phá thử (bài tập)

1. Sửa `OptimisticRetry.MAX_ATTEMPTS = 1`, build lại, chạy `load-tests/run.sh webhook-vs-cancel -Jthreads=3 -Jloops=100`. Trước khi chạy, **đoán**: cancel thua version sẽ nhận gì? Webhook có bao giờ thua version không (gợi ý: row lock)? Có bất biến HARD nào vỡ không, hay chỉ JMeter báo lỗi? Rồi so với kết quả.
2. Thêm `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE: "30"` vào `environment:` của gateway trong `load-tests/docker-compose.loadtest.yml` (compose không tự chuyển biến môi trường vào container), rồi chạy lại baseline 200 thread. Throughput, Hikari pending và Tomcat busy đổi thế nào? Pool có thật là nguyên nhân không? Nút thắt tiếp theo là gì?
3. Đặt `MOCK_EXECUTOR_THREADS=2 MOCK_EXECUTOR_QUEUE=50 MOCK_CALLBACK_DELAY_MS=100` và chạy đua với 50 thread. `run.sh` báo gì? Kết quả lượt chạy đó còn nói gì về gateway không?
