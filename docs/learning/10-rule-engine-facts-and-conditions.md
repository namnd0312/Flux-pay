# 10 - Rule engine: luật là dữ liệu, không phải code

> Increment 3a, phase 1. Mục tiêu: hiểu **rule engine** là gì ở mức tối thiểu — **fact**, **condition**, **action**, **conflict resolution** — bằng cách tự viết một cái nhỏ, và hiểu vì sao luật nằm trong database thay vì trong `if`.

## 0. Ghi chú failure demo

Yêu cầu: "không nhận payment trên 50.000.000". Cách tự nhiên nhất là một dòng trong `PaymentOrchestrator.create()`:

```java
if (amount > 50_000_000) {
    throw new RiskRejectedException("AMOUNT_OVER_LIMIT");
}
```

Chạy `RiskRejectAtCreateIT` với đúng dòng `if` đó (đo 2026-11-26): **4 xanh, 3 đỏ**.

| Test | Với `if` cứng | Với rule engine |
|---|---|---|
| trên hạn mức → 422, 0 dòng, 0 provider call | xanh | xanh |
| đúng bằng hạn mức → 201 | xanh | xanh |
| luật của merchant khác không áp dụng | xanh | xanh |
| luật sai ngữ pháp không gây 500 | xanh | xanh |
| thêm luật "trên 2.000.000" bằng SQL → request kế tiếp bị chặn | **đỏ** (`expected 422 but was 201`) | xanh |
| luật riêng của một merchant thắng luật chung | **đỏ** (vẫn trả `AMOUNT_OVER_LIMIT`) | xanh |
| tắt luật rồi gửi lại với key mới → 201 | **đỏ** (`expected 201 but was 422`) | xanh |

Dòng `if` *đúng* cho đúng một yêu cầu. Nó sai ở chỗ **ai được đổi luật và đổi nhanh cỡ nào**: mỗi lần đổi hạn mức, thêm ngoại lệ cho một merchant, hay tắt tạm một luật đều là sửa code → build → deploy. Người quyết định luật (vận hành, risk) không phải người deploy.

## 1. Bốn mảnh của một rule engine

| Mảnh | Nghĩa | Ở đây |
|---|---|---|
| **Fact** | Những gì luật được phép nhìn | `RiskFacts`: `amount`, `provider`, `merchantId`, `orderId`, `hourOfDay` |
| **Condition** | Biểu thức đúng/sai trên fact | cây JSON trong cột `risk_rules.condition` |
| **Action** | Làm gì khi condition đúng | `REJECT` (phase 3 thêm `REVIEW`) |
| **Conflict resolution** | Nhiều luật cùng khớp thì nghe ai | theo `(priority, id)`, **luật khớp đầu tiên quyết định** |

Một luật là một dòng:

```sql
select id, name, merchant_id, priority, condition, action, reason_code, enabled from risk_rules;
--  1 | global-amount-limit | (null) | 100 | {"field":"amount","op":"gt","value":50000000} | REJECT | AMOUNT_OVER_LIMIT | t
```

Luồng trong `create()`: resolve provider → dựng `RiskFacts` → `RiskEngine.evaluate(facts)` → `REJECT` thì ném `RiskRejectedException` (**422 `RISK_REJECTED`**), ngược lại chạy tiếp luồng cũ. Việc hỏi luật nằm **trước** tx1, nên một lần từ chối không để lại dòng `payments` nào và không gọi provider.

## 2. Ngữ pháp của condition

```
node := {"all":[node, ...]} | {"any":[node, ...]} | leaf
leaf := {"field": tên, "op": toán tử, "value": giá trị}
op   := eq | ne | gt | gte | lt | lte | in
```

Ví dụ "ban đêm (22h-6h) và trên 20 triệu, qua MOCK hoặc VNPAY":

```json
{"all":[
  {"any":[{"field":"hourOfDay","op":"gte","value":22},{"field":"hourOfDay","op":"lt","value":6}]},
  {"field":"amount","op":"gt","value":20000000},
  {"field":"provider","op":"in","value":["MOCK","VNPAY"]}
]}
```

Có **hai bước tách rời**:

1. **Compile** (`RuleCompiler`): đọc JSON, kiểm tra mọi thứ, dựng cây `Condition` (`All`, `Any`, `Leaf`). Sai thì ném `InvalidRuleException` nói rõ sai ở đâu.
2. **Evaluate** (`Condition.test(facts)`): duyệt cây, trả đúng/sai. Không I/O, không đồng hồ, không state — nên test được không cần Spring, và một cây dùng chung cho nhiều request.

Vì compile đã kiểm tra hết, evaluate **không bao giờ đổi kiểu**: số so với số (`Long`), chuỗi so bằng với chuỗi.

Compiler từ chối: field lạ, toán tử lạ (kể cả `GT` viết hoa), thừa/thiếu key, `all`/`any` rỗng, lồng sâu quá 5 tầng, `gt/gte/lt/lte` trên field chuỗi, `in` không kèm mảng khác rỗng, và giá trị sai kiểu. Với field số, giá trị phải là **số nguyên JSON**: `50000000` được; `"50000000"`, `5e7`, `50000000.0` bị từ chối. Lý do: nếu lặng lẽ ép kiểu, luật có thể mang nghĩa khác với thứ người viết gõ. Một luật bị bỏ qua (có log) dễ phát hiện hơn một luật chạy sai nghĩa.

## 3. Vì sao whitelist field, không dùng SpEL

Spring có sẵn SpEL; viết `amount > 50000000 and provider == 'MOCK'` gọn hơn cây JSON nhiều. Không dùng, vì chuỗi luật nằm trong database:

- SpEL là **ngôn ngữ biểu thức đầy đủ**: gọi method, truy cập type (`T(java.lang.Runtime)`), dựng object. Ai `UPDATE` được bảng `risk_rules` là chạy được code trong JVM của gateway. Muốn an toàn phải cấu hình evaluation context giới hạn, và phải nhớ giữ nó giới hạn mãi.
- Với whitelist, **toàn bộ khả năng của luật là cái bảng `RiskFacts.FIELD_TYPES`**. Luật không nhìn được thứ gì ngoài năm field, không gọi được gì. Bề mặt tấn công là 7 toán tử.
- Kiểm tra được **lúc nạp** thay vì lúc chạy: tên field sai bị bắt khi compile, không phải khi request đầu tiên khớp nhánh đó.

Cái giá: muốn thêm khả năng (ví dụ "số payment trong 1 phút") phải sửa code để thêm fact. Đó là chủ ý — fact là **hợp đồng** giữa code và người viết luật.

## 4. First-match hay collect-all

Hai cách giải quyết khi nhiều luật cùng khớp:

- **First-match** (ở đây, giống firewall): xếp thứ tự, luật khớp đầu tiên quyết định, dừng. Dễ giải thích ("vì luật số 7"), nhưng **thứ tự là một phần của logic**: đổi `priority` là đổi hành vi.
- **Collect-all**: chạy hết, gom mọi luật khớp, rồi gộp (ví dụ lấy action nặng nhất, hoặc cộng điểm rủi ro). Mạnh hơn, hợp với chấm điểm; khó trả lời "vì sao bị chặn" hơn.

Thứ tự ở đây: `priority` tăng dần, rồi `id`. `merchant_id` null = luật chung; luật có `merchant_id` chỉ được xét cho merchant đó. **Luật riêng không tự động thắng luật chung** — nó thắng khi có số `priority` nhỏ hơn (`RiskEngineTest.aMerchantRuleWinsOnlyThroughALowerPriorityNumber`). Không có luật ngầm nào để phải nhớ.

Không luật nào khớp → `ALLOW`. Engine này là **default-allow**: nó chặn cái đã biết là xấu.

## 5. Luật hỏng: fail-open cho riêng luật đó

`JSONB` chỉ bảo đảm cột chứa JSON hợp lệ, không bảo đảm đúng ngữ pháp luật. Khi một dòng không compile được, `RiskEngine` log ERROR và **bỏ qua dòng đó**:

```
ERROR RiskEngine : Risk rule 2 is invalid and was skipped: unknown field "customerEmail"
```

Hai lựa chọn, đều có giá:

| | Luật hỏng → | Hậu quả của một lỗi gõ |
|---|---|---|
| **Fail-open** (đã chọn, 2026-11-26) | bỏ qua luật đó | payment mà luật định chặn sẽ **đi qua** |
| **Fail-closed** | từ chối mọi request | một dòng SQL sai làm sập toàn bộ việc tạo payment |

Chọn fail-open vì ở đây một luật thiếu ít nguy hiểm hơn cả gateway ngừng nhận tiền. Nhưng phải nói thẳng: **không có cảnh báo nào** ngoài dòng log. Gateway thật thường validate luật ở cửa vào (API/UI quản trị) nên luật hỏng không vào được bảng.

## 6. Tương tác với idempotency

`422` nằm trong `IdempotencyFilter.STORABLE_STATUSES`, nên một lần từ chối **được lưu theo key**. Gửi lại cùng key → replay đúng `422` đó, **không hỏi lại luật**, kể cả khi luật đã bị tắt (`theSameIdempotencyKeyReplaysTheRejectionEvenAfterTheRuleIsGone`). Đúng với nghĩa của idempotency key (cùng key → cùng câu trả lời); merchant muốn thử lại sau khi luật đổi phải dùng key mới.

## Giới hạn đã biết

- **Đọc và compile luật trên mọi request.** Mỗi lần create tốn thêm một query và một lượt parse JSON cho mọi luật. Cố ý để vậy ở phase này; phase 2 đo cái giá rồi sửa bằng cache.
- **Không có dấu vết quyết định.** Request được `ALLOW` hay `REJECT` vì luật nào, với fact gì — không lưu ở đâu ngoài response. Phase 2 thêm bảng audit.
- **Fact không có state.** Không viết được luật kiểu "quá 5 payment trong 1 phút" (velocity); ngoài phạm vi increment này.
- **Đổi luật chỉ bằng SQL**, không có API/UI, không có lịch sử phiên bản luật.
- `hourOfDay` tính theo `fluxpay.risk.zone` (mặc định `Asia/Ho_Chi_Minh`), không theo múi giờ của merchant.

## Tự kiểm bằng Postman / psql

Folder **Phase 10 - Risk rules**. Mở psql: `docker compose exec db psql -U flux_pay flux_pay`.

1. Request 1 (1.000.000) → `201`. Request 2 (50.000.001) → `422 RISK_REJECTED`, `detail` chứa `AMOUNT_OVER_LIMIT`. Kiểm tra: `select count(*) from payments where amount = 50000001;` → 0.
2. Request 3 (6.000.000) → `201`. Chạy `update risk_rules set condition = '{"field":"amount","op":"gt","value":5000000}' where name = 'global-amount-limit';` rồi gửi lại request 3 → `422`, **không restart**. Trả lại giá trị cũ sau khi thử.

## Tự phá thử (bài tập)

1. Thêm một luật hỏng có `priority = 1`: `insert into risk_rules (name, priority, condition, action, reason_code) values ('typo', 1, '{"field":"amout","op":"gt","value":1}', 'REJECT', 'TYPO');`. Gửi request 1. Kết quả là gì, log nói gì? Giờ sửa `RiskEngine.compile` để **ném lại** exception thay vì bỏ qua: test nào đỏ, và merchant thấy mã lỗi gì?
2. Trong `RuleCompiler.literal`, chấp nhận chuỗi cho field số bằng `Long.parseLong`. Test nào đỏ? Viết một luật mà sau thay đổi này mang nghĩa **khác** với thứ người viết nghĩ.
3. Thêm hai luật cùng `priority = 50`, cùng khớp một request, khác `reason_code`. Luật nào thắng, vì sao? Điều gì xảy ra với câu trả lời nếu bỏ `, id` khỏi `order by` trong `RiskRuleStore`?
4. Viết luật "chỉ chặn trong giờ hành chính (9h-17h) với đơn trên 10 triệu". Cần `all`, `any`, hay cả hai?
5. Đổi `decide` thành collect-all: gom mọi luật khớp và trả về danh sách `reason_code`. `RiskDecision` phải đổi thế nào, và test `firstMatchInOrderWins` nên khẳng định điều gì?
