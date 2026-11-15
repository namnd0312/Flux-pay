# 07 - Webhook đến sớm, đến lộn thứ tự, và pessimistic lock

> Increment 2, phase 2. Mục tiêu: hiểu vì sao **HTTP status là tín hiệu retry duy nhất** giữa provider và gateway, phân biệt từ chối **vĩnh viễn** với từ chối **tạm thời**, và biết row lock (`SELECT ... FOR UPDATE`) giải quyết được gì, không giải quyết được gì.

## 0. Ghi chú failure demo

Create chạy hai transaction quanh một lời gọi provider: tx1 ghi `CREATED` → gọi provider (không có transaction) → tx2 ghi `provider_ref` + `PENDING`. Provider nhanh có thể bắn webhook **trước khi tx2 commit**.

Demo (`EarlyWebhookIT`, bản trước khi sửa): fake provider **giữ** lời gọi create lại giữa chừng, test gửi webhook SUCCESS vào đúng khe đó, rồi thả create, rồi gửi lại webhook y hệt.

| Bước | Trước (đo 2026-11-13) | Sau |
|---|---|---|
| Webhook sớm | `200` `rejectReason=UNKNOWN_PAYMENT`, payment `CREATED` | `503 WEBHOOK_RETRY_LATER` (`reason=PAYMENT_NOT_READY`), event `RETRYABLE` |
| Create được thả | `201`, payment `PENDING` | `201`, payment `PENDING` |
| Provider gửi lại | `200` `duplicate=true`, payment **vẫn `PENDING`** | `200` `applied=true`, payment **`SUCCEEDED`** |

"Trước": khách đã trả tiền, gateway đã trả 200 hai lần, và payment sẽ nằm `PENDING` cho tới khi sweeper cho nó `EXPIRED`. Không có exception, không có log lỗi.

## 1. Hai sự thật về webhook

1. **At-least-once.** Provider gửi cho tới khi nhận 2xx, nên có trùng (note 03).
2. **Không có thứ tự.** Hai sự kiện của cùng một payment có thể tới theo thứ tự bất kỳ, và một sự kiện có thể tới *trước* cả response của lời gọi API tạo ra nó. Tài liệu webhook của Stripe nói thẳng cả hai điều này.

Provider không đọc body của ta. Nó chỉ nhìn **status**: 2xx = "xong, đừng gửi nữa"; còn lại = "gửi lại sau".

## 2. Bẫy: dedup trước, validate sau

Luồng cũ: insert dòng dedup → validate → trả 200 dù kết quả là gì. Với webhook sớm:

- lookup theo `provider_ref` không ra (tx2 chưa ghi) → `UNKNOWN_PAYMENT` → 200;
- dòng dedup đã nằm đó → lần gửi lại bị coi là trùng.

Hai quyết định đúng riêng lẻ (dedup, luôn 200) ghép lại thành mất dữ liệu. Thiếu sót: không phân biệt hai loại từ chối.

| | Vĩnh viễn | Tạm thời |
|---|---|---|
| Ví dụ | sai amount, payment đã terminal, payment không tồn tại | payment còn `CREATED`, dòng đang bị khóa, lần giao trước chưa xong |
| Gửi lại có ích? | không bao giờ | có |
| HTTP | **200** | **503** + `Retry-After` |
| Dòng event | `REJECTED` | `RETRYABLE` |

Trả 200 cho thứ tạm thời → provider dừng → mất. Trả 5xx cho thứ vĩnh viễn → provider retry vô ích hàng giờ.

## 3. Sửa: kết quả `RETRYABLE` + reclaim

- Gateway trả 503 và đánh dấu event `RETRYABLE`.
- Khi provider gửi lại, insert dedup vẫn "thua" (dòng đã có), nên có bước thứ hai: **reclaim**.

```sql
update webhook_events
set processing_status = 'RECEIVED', reject_reason = null, processed_at = null,
    duplicate_count = duplicate_count + 1
where provider_code = :pc and event_id = :eid
  and processing_status = 'RETRYABLE' and raw_body = :raw
returning id;
```

Cùng mẫu với lease take-over ở note 05: **một câu UPDATE có điều kiện là trọng tài**. Hai lần gửi lại chạy song song thì đúng một bên nhận được `id`. `raw_body = :raw` bắt buộc byte phải giống hệt, để `raw_body` trong ledger luôn là đúng thứ đã được ký và áp dụng.

Lần gửi lại của một `event_id` đã biết nhận gì:

| Trạng thái đang lưu | Trả lời |
|---|---|
| `RETRYABLE`, body giống | reclaim, xử lý lại |
| `RETRYABLE`, body khác | 200 `EVENT_BODY_MISMATCH`, không xử lý |
| `RECEIVED` (lần giao trước **đang chạy**) | **503** `EVENT_IN_PROGRESS` |
| `APPLIED` / `REJECTED` | 200 duplicate |

Dòng thứ ba dễ làm sai: lần giao đang chạy có thể kết thúc bằng `RETRYABLE`. Nếu trả 200 cho bản gửi lại, provider dừng đúng lúc ta cần nó gửi tiếp.

Ba câu ghi kết quả (`markApplied/Rejected/Retryable`) đều có `and processing_status = 'RECEIVED'`: chỉ lần giao đang giữ event mới ghi được kết quả.

## 4. "Chưa sẵn sàng" hay "không tồn tại"?

Webhook mang `merchantRef` = **payment id của ta**, và dòng đó có từ tx1. Khi lookup theo `provider_ref` không ra, ta đọc **một lần** theo id rồi quyết định từ **đúng một dòng đó**:

| Dòng tìm được | Kết quả |
|---|---|
| không có | `REJECTED UNKNOWN_PAYMENT` |
| `CREATED` | `RETRYABLE PAYMENT_NOT_READY` |
| `provider_ref` trùng với event | đi tiếp đường bình thường (tx2 vừa commit) |
| đã terminal, `provider_ref` null | `REJECTED PAYMENT_NEVER_ACCEPTED`, **có lưu `payment_id`** |
| `provider_ref` khác | `REJECTED REF_MISMATCH` |

Vì sao nhấn mạnh "một dòng": bản thiết kế đầu đọc hai lần (theo ref, rồi theo id) và ghép hai câu trả lời. Nếu tx2 commit **giữa** hai lần đọc, lần một thấy "không có ref", lần hai thấy `PENDING` → kết luận sai "unknown". Đó là **check-then-act trên hai snapshot** — cùng họ với lỗi ở note 05 và 06. Dòng thứ ba trong bảng tồn tại để đóng khe đó (`WebhookFallbackDecisionTest`).

Không cho `CREATED → SUCCEEDED` đi thẳng: tx2 sau đó sẽ fail khi chuyển sang `PENDING`, và create trả lỗi cho một đơn đã thanh toán.

## 5. Lộn thứ tự: terminal đầu tiên thắng

`FAILED` rồi `SUCCESS` (hai `event_id` khác nhau): payment giữ `FAILED`, sự kiện thứ hai `REJECTED ILLEGAL_TRANSITION`. Ngược lại cũng vậy (`OutOfOrderWebhookIT`). State machine từ note 01 đã lo việc này; phase này chỉ chứng minh và ghi lại. Dòng `REJECTED` chính là dấu vết cho reconciliation: provider nói một đằng, ta ghi một nẻo.

## 6. Pessimistic lock

Optimistic (note 06): không khóa, phát hiện xung đột **lúc ghi**, kẻ thua làm lại. Pessimistic: khóa **lúc đọc**, kẻ đến sau **xếp hàng**.

```sql
select ... from payments p1_0 where p1_0.id = ? for no key update of p1_0
```

Đây là SQL thật Hibernate 7 sinh ra cho `@Lock(PESSIMISTIC_WRITE)` trên PostgreSQL. Lưu ý là `FOR NO KEY UPDATE`, không phải `FOR UPDATE`: vẫn chặn mọi UPDATE/lock khác trên dòng, nhưng **không** chặn `FOR KEY SHARE` — loại khóa mà một transaction **khác** cần khi insert dòng con có foreign key tới `payments` (ví dụ audit row của cancel). Transaction đó vì thế không bị chặn ở câu insert; nó chỉ phải chờ khi tới câu UPDATE `payments` của chính nó. (Một transaction không bao giờ tự chặn bởi khóa của mình.)

Lúc viết note này có công tắc `fluxpay.webhook.lock-mode` (`pessimistic` | `optimistic`) để phase 4 đo cả hai. Sau khi đo (note 09, mục 3), nhánh optimistic đã bị **bỏ**: đường webhook giờ luôn lấy row lock.

| | Optimistic + retry | Pessimistic |
|---|---|---|
| Lúc đọc | không khóa | giữ row lock tới hết transaction |
| Khi tranh chấp | một bên làm lại | một bên chờ |
| Chi phí khi không tranh chấp | gần 0 | một lần lấy khóa |
| Rủi ro | retry nhiều nếu tranh chấp dày | chờ lâu, deadlock nếu khóa nhiều dòng |

Ở đây mỗi transaction chỉ khóa **một** dòng nên không có vấn đề thứ tự khóa.

**Hai cơ chế sống chung.** Chỉ webhook lấy row lock; cancel và sweeper thì không. Khi webhook đang giữ khóa, câu UPDATE của cancel phải chờ; webhook commit xong, cancel ghi với version cũ → thua version check → retry (note 06) → 409. Khóa xếp hàng writer; `@Version` vẫn bắt kẻ không lấy khóa. `WebhookVsCancelRaceIT` kiểm bộ bất biến đó, và kiểm thêm rằng đường webhook **không bao giờ** retry (nếu có, row lock không còn hiệu lực).

### Lock timeout

Chờ khóa vô hạn = request thread treo. Nên transaction đặt giới hạn:

```sql
select set_config('lock_timeout', '2000', true);   -- true = chỉ trong transaction này
```

`SET LOCAL lock_timeout = ?` **không** nhận bind parameter (là utility statement), nên dùng dạng hàm. Hết giờ → `RETRYABLE LOCK_TIMEOUT` + 503: lại là "chưa được lúc này". `WebhookPessimisticLockIT` kiểm cả ba: chờ rồi áp dụng được; hết giờ thì 503 và gửi lại áp dụng được; sau transaction đó, chính connection vừa dùng đã trở về `lock_timeout = 0` (transaction-local là cam kết của PostgreSQL với tham số `true`).

Mẹo test: "thread đang bị chặn" xảy ra **trong PostgreSQL** nên không latch nào thấy được. Test hỏi thẳng database: `pg_stat_activity` có backend với `wait_event_type = 'Lock'`. (Trong một transaction, view này bị đóng băng ở lần đọc đầu; phải gọi `pg_stat_clear_snapshot()` trước mỗi lần hỏi.)

### Row lock KHÔNG cứu được webhook sớm

Lúc create đang gọi provider thì **không có transaction nào mở**, tx2 chưa bắt đầu — không có gì để chờ. Khóa chỉ xếp hàng được những transaction *đang tồn tại*. Cách duy nhất ở thiết kế đồng bộ là bảo provider quay lại (mục 3).

## 7. Mock provider biết retry

`WebhookSender` giờ gửi lại cho tới khi nhận 2xx: tối đa 6 lần, nghỉ 0.5s rồi gấp đôi (tổng 15.5s, dài hơn 7s tối đa gateway có thể tốn cho lời gọi provider). Luật retry giống provider thật: **5xx / 429 / không trả lời** thì gửi lại; **4xx khác** (401 sai chữ ký, 404, 413) thì dừng, vì gửi lại y hệt không bao giờ khác đi. Mọi lần gửi cùng `eventId`, cùng byte.

Knob mới: `MOCK_CALLBACK_AUTO_PAY=SUCCESS` (trả tiền ngay lúc tạo session → webhook sớm), `MOCK_CREATE_DELAY_MS` (provider chậm), `MOCK_EXECUTOR_THREADS/QUEUE` (pool gửi webhook; webhook bị rớt vì đầy pool được log và đếm ở `mock.webhook.rejected`).

## Giới hạn đã biết

Để dành cho increment 3 (inbox + consumer) và increment 4 (reconciliation):

- **Đã trả tiền nhưng `FAILED`.** Provider nhận, khách trả, nhưng create timeout và bị compensate về `FAILED` (hoặc JVM chết trước tx2 và sweeper fail dòng đó). Webhook gửi lại → `REJECTED PAYMENT_NEVER_ACCEPTED`, có link tới payment. `FAILED` không "live" nên merchant tạo lại được đơn. Cùng dạng với `FAILED` rồi `SUCCESS`.
- **Provider bỏ cuộc.** Hết 6 lần mà payment vẫn chưa sẵn sàng → event nằm `RETRYABLE`.
- **Event kẹt `RECEIVED`.** JVM chết giữa chừng, hoặc database lỗi tới mức không ghi nổi kết quả. Các lần gửi lại nhận 503 (không còn bị nuốt bằng 200), cho tới khi provider bỏ cuộc.
- **Ledger có thể ghi `REJECTED` cho sự kiện đã được áp dụng.** Nếu transaction áp dụng đã commit nhưng lời gọi vẫn ném lỗi (mất ack của commit), event được nhả về `RETRYABLE`; lần gửi lại thấy payment đã terminal → `REJECTED ILLEGAL_TRANSITION`. Payment vẫn đúng và chỉ được áp dụng một lần; chỉ dòng ledger là lệch.
- Webhook ký đúng nhưng thiếu `providerRef`/`merchantRef` hoặc `status` lạ → `REJECTED MALFORMED_EVENT`, không đụng tới payment nào.
- Chưa chạy demo end-to-end bằng `docker compose` với mock thật ở phase này; phần gateway và phần mock được test riêng.

## Tự phá thử (bài tập)

1. Trong `WebhookEventStore.reclaimRetryable`, đổi thành "select trước, thấy `RETRYABLE` thì update" (hai câu). Chạy `EarlyWebhookIT.concurrentRedeliveriesOfARetryableEventApplyItExactlyOnce` nhiều lần. Điều gì có thể xảy ra, và vì sao payment **vẫn** không bị ghi hai lần? (Gợi ý: còn lớp nào phía sau?)
2. Trong `WebhookIngestionService.answerForKnownEvent`, cho `RECEIVED` trả duplicate 200. Test nào đỏ? Viết ra kịch bản mất webhook tương ứng.
3. Đặt `fluxpay.webhook.lock-timeout: 50ms` và chạy `WebhookVsCancelRaceIT`. Kết quả có còn đúng không? Cái gì thay đổi?
4. Bỏ dòng "`provider_ref` trùng → đi tiếp" trong `decideWithoutProviderRef`. Test nào bắt được? Vì sao không viết được test end-to-end cho nó?
