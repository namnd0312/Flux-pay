# 11 - Cache luật và dấu vết quyết định

> Increment 3a, phase 2. Mục tiêu: hiểu cái giá của việc **đọc cấu hình trên mọi request**, cách cache nó mà không để request nào thấy trạng thái dở dang (**atomic swap**), cái giá mới phải trả (**staleness window**), và vì sao một quyết định tự động cần **audit trail** ghi cùng transaction với thứ nó quyết định.

## 0. Ghi chú failure demo

Hai chỗ hỏng của bản phase 1.

**(a) Mỗi request tạo payment đọc lại toàn bộ bảng luật.** Đếm câu SQL bằng `StatementCounter` (test helper bọc `DataSource`), đo 2026-11-26:

| Request | Phase 1 | Phase 2 |
|---|---|---|
| create được phép (201) | 8 câu, trong đó 1 `select ... from risk_rules` | 8 câu: **0** `risk_rules`, 1 `insert into risk_decisions` |
| create bị từ chối (422) | 4 câu, trong đó 1 `select ... from risk_rules` | 4 câu: **0** `risk_rules`, 1 `insert into risk_decisions` |

Một `SELECT` trông rẻ. Nhưng nó chạy ở **mọi** request, giữ thêm một connection trong lúc chạy (note 09: hàng đợi đầu tiên hình thành ở connection pool), và kéo theo compile lại JSON của mọi luật. Luật thì gần như không đổi: đọc 1.000 lần để nhận 1.000 câu trả lời giống nhau.

**(b) Quyết định không để lại dấu vết.** Phase 1 không có bảng nào ghi lại quyết định. Merchant hỏi "vì sao đơn X bị từ chối hôm qua?" — không trả lời được: response đã mất, và luật có thể đã bị sửa từ hôm đó. Request được *cho qua* cũng vậy: không biết lúc đó luật nào đang bật.

## 1. Cache: compile một lần, dùng nhiều lần

`RiskRuleCache` giữ `List<CompiledRule>` trong bộ nhớ. `RiskEngine.evaluate` chỉ đọc danh sách đó — **không còn I/O trên đường request**.

```
khởi động ──> load + compile ──> rules
mỗi 10s   ──> load + compile ──> rules   (fluxpay.risk.refresh-interval)
request   ──> đọc rules ──> quyết định
```

Đây là **polling**: gateway tự hỏi lại database theo chu kỳ. Phương án khác là **push** (Postgres `LISTEN/NOTIFY`, hoặc một message khi luật đổi): gần như tức thời nhưng thêm một kênh phải giữ sống, và vẫn cần polling dự phòng khi lỡ mất thông báo. Polling đủ cho thứ đổi vài lần một ngày.

## 2. Atomic swap: không request nào thấy danh sách dở dang

Cách **sai** dễ viết nhất:

```java
rules.clear();                 // request tới đúng lúc này thấy 0 luật → cho qua tất cả
rules.addAll(loadAndCompile());
```

Cách đang dùng:

```java
private volatile List<CompiledRule> rules;        // danh sách bất biến (List.copyOf)

List<CompiledRule> fresh = load();                // dựng XONG danh sách mới ở bên ngoài
rules = fresh;                                    // rồi mới đổi MỘT tham chiếu
```

- Danh sách **bất biến**; refresh không sửa nó mà **thay** nó. Request đang chạy dở vẫn cầm danh sách cũ, nguyên vẹn (`RiskRuleCacheTest.refreshReplacesTheListInsteadOfChangingIt`).
- Gán một tham chiếu là atomic; `volatile` bảo đảm thread khác **thấy** giá trị mới. Bên đọc không cần lock.
- Vì thế mỗi request thấy **hoặc toàn bộ luật cũ, hoặc toàn bộ luật mới**.

Mẫu này tên là **copy-on-write** / immutable snapshot: đọc rất nhiều, ghi rất hiếm.

## 3. Cái giá: staleness window

Sửa luật bằng SQL xong, gateway **chưa biết**. Trong tối đa một `refresh-interval` (10s) nó vẫn quyết định bằng luật cũ:

```
t=0s   UPDATE hạn mức 50tr → 5tr
t=3s   create 6tr → 201        ← vẫn luật cũ (RiskRuleCacheIT.aThresholdChangedBySqlAppliesAfterRefreshAndNotBefore)
t=10s  refresh
t=11s  create 6tr → 422
```

Cache nào cũng đổi **độ tươi** lấy **tốc độ**; câu hỏi chỉ là cửa sổ rộng bao nhiêu thì chấp nhận được. Với "chặn khẩn cấp một merchant đang bị tấn công", 10s có thể là quá dài — khi đó cần push hoặc một lệnh ép refresh. Ở đây chấp nhận 10s.

Nhiều instance (chưa có): mỗi instance có đồng hồ refresh riêng, nên trong cửa sổ đó **hai instance có thể trả lời khác nhau** cho cùng một request.

## 4. Hai kiểu hỏng, hai cách xử lý

| Hỏng | Xử lý | Vì sao |
|---|---|---|
| **Một dòng** không compile được | bỏ dòng đó (log ERROR), nạp các dòng còn lại | quyết định fail-open ở note 10 |
| **Không đọc được bảng** (database lỗi) | bỏ cả lần refresh, **giữ danh sách cũ**, `refresh()` trả `false` | danh sách rỗng = lặng lẽ cho qua mọi thứ |
| Lần nạp **đầu tiên** thất bại | ứng dụng **không khởi động** | chưa có danh sách cũ nào để dựa vào |

Hệ quả cần biết của dòng đầu: luật đang chạy tốt mà bị **sửa thành hỏng** thì ở lần refresh kế tiếp nó **biến mất**, không giữ nghĩa cũ (`aRuleEditedIntoABrokenOneIsDroppedAtRefresh`). Và dòng ERROR lặp lại ở mỗi lần refresh chừng nào dòng hỏng còn đó — hiện là tín hiệu cảnh báo duy nhất.

Lần nạp đầu chạy trong `afterSingletonsInstantiated()`: sau khi mọi bean (kể cả Flyway) đã xong, và **trước** khi web server nhận request. Nếu dùng `ApplicationRunner` thì có một khe: server đã mở cổng mà cache còn trống.

## 5. Audit trail: mỗi lần đánh giá một dòng

```sql
select id, order_id, payment_id, decision, matched_rule_id, reason_code, facts, created_at
from risk_decisions order by id desc limit 2;
-- 2 | ORD-B | (null)   | REJECT | 1      | AMOUNT_OVER_LIMIT | {"amount": 50000001, "orderId": "ORD-B", "provider": "MOCK", "hourOfDay": 7, "merchantId": "0000..."} | ...
-- 1 | ORD-A | 7c1e-... | ALLOW  | (null) | (null)            | {"amount": 1000000, ...}                                                                              | ...
```

- `facts` là **bản chụp** đúng những gì luật đã thấy. Luật có thể bị sửa/xóa sau đó (`matched_rule_id` cố ý **không có foreign key**), nhưng bản chụp vẫn giải thích được quyết định.
- Ghi cả `ALLOW`. "Vì sao payment này *được* đi qua?" cũng là câu hỏi sẽ có người hỏi.

**Ghi ở đâu so với transaction:**

| Quyết định | Ghi lúc nào | Kết quả |
|---|---|---|
| `ALLOW` | **trong tx1**, ngay sau `insert payments`, kèm `payment_id` | payment và quyết định cùng commit hoặc cùng rollback |
| `REJECT` | một câu `INSERT` tự commit, rồi mới ném exception | không có payment nào để gắn; `payment_id` null |

Nếu ghi `ALLOW` **trước** tx1 trong transaction riêng: đơn trùng (`409 ORDER_ALREADY_EXISTS`) sẽ để lại một dòng "ALLOW" cho một payment không hề tồn tại. Ghi **sau** tx1 trong transaction riêng: JVM chết ở giữa → có payment mà không có lý do. Cùng transaction thì hai trường hợp đó không thể xảy ra (`aDuplicateOrderLeavesNoSecondDecision`). Đây là cùng một ý với ledger `payment_transitions`: bản ghi về một thay đổi phải nằm chung transaction với thay đổi đó.

Idempotent replay **không** đánh giá lại nên không thêm dòng (`anIdempotentReplayIsNotEvaluatedAgain`). "Mỗi lần *đánh giá* một dòng", không phải "mỗi HTTP request một dòng".

## 6. Bảng audit không bao giờ được chứa gì

`facts` chỉ lấy từ `RiskFacts.asMap()` — đúng năm field (`theFactsSnapshotHoldsTheFiveFactsAndNothingElse`). Không ghi request body thô, header, chữ ký, `Idempotency-Key`, secret. Bảng audit thường sống lâu hơn và được nhiều người đọc hơn bảng nghiệp vụ; thứ gì lọt vào đó rất khó lấy ra. Khi thêm fact mới (ví dụ email khách hàng) phải tự hỏi: dữ liệu này có được phép nằm trong một bảng giữ nhiều năm không.

## Giới hạn đã biết

- **Staleness tới 10s**, không có lệnh ép refresh ngoài restart.
- **Không ghi được audit `REJECT` → request trả 500** thay vì 422 (thà hỏng còn hơn từ chối mà không ghi lại). Ngược lại, audit `REJECT` có thể đã commit mà response không tới được merchant; chấp nhận — dòng đó nói "đã quyết định từ chối".
- **`risk_decisions` chỉ lớn lên**: một dòng mỗi lần đánh giá, chưa có job dọn hay partition.
- Đơn trùng (409) không để lại dấu vết đánh giá nào, dù luật đã chạy.
- Luật hỏng chỉ có log ERROR lặp lại, không metric, không alert.
- Số câu SQL ở mục 0 đếm qua MockMvc trong test, không phải đo latency dưới tải; chưa chạy lại JMeter cho phase này.

## Tự kiểm bằng Postman / psql

1. Gửi request 1 và 2 của folder **Phase 10 - Risk rules**, rồi chạy câu `select` ở mục 5. Phải thấy một dòng `ALLOW` có `payment_id` và một dòng `REJECT` có `matched_rule_id`.
2. `update risk_rules set condition = '{"field":"amount","op":"gt","value":5000000}' where name = 'global-amount-limit';` rồi gửi request 3 **ngay lập tức** và lại sau ~10s. Lần đầu có thể vẫn `201`, lần sau `422`. Trong log gateway: `Risk rules changed: 1 active`. Trả lại `50000000` khi xong.
3. Gửi request 2 hai lần với **cùng** `Idempotency-Key` (đặt header tay): `risk_decisions` chỉ thêm **một** dòng.

## Tự phá thử (bài tập)

1. Đổi `refresh()` thành "sửa tại chỗ": dùng một `ArrayList` không `volatile`, `clear()` rồi `addAll()`. Test nào trong `RiskRuleCacheTest` đỏ? Test đó có bắt được lỗi "request thấy 0 luật giữa `clear` và `addAll`" không? Nếu không, vì sao lỗi đó khó viết thành test?
2. Trong `RiskRuleCache.refresh()`, bỏ `try/catch` quanh `load()`. Test nào đỏ? Trong một gateway đang chạy, thread scheduler gặp exception thì lần refresh sau có còn chạy không?
3. Trong `PaymentOrchestrator.create()`, chuyển `riskDecisions.record(...)` của nhánh `ALLOW` lên **trước** `tx.executeWithoutResult`. Test nào trong `RiskDecisionAuditIT` đỏ, và dòng thừa trong bảng nói điều gì sai sự thật?
4. Đặt `fluxpay.risk.refresh-interval: 5m`. Viết ra một tình huống vận hành mà 5 phút là không chấp nhận được, và một tình huống mà nó hoàn toàn ổn.
5. Thêm fact `returnUrl` vào `RiskFacts`. Nó sẽ tự động xuất hiện ở đâu ngoài luật? Có nên không?
