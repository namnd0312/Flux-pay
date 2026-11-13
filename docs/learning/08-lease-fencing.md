# 08 - Lease fencing: hết hạn không có nghĩa là đã chết

> Increment 2, phase 3. Mục tiêu: hiểu vì sao một **lease chỉ có hạn dùng** là chưa an toàn, **zombie writer** là gì, và **fencing token** chặn nó bằng một điều kiện trong câu ghi như thế nào.

## 0. Ghi chú failure demo

Nhắc lại note 05: key `IN_PROGRESS` giữ một **lease** 60s. Quá hạn, request khác được **take over** vì worker cũ "coi như đã chết". Nhưng `complete()` và `abandon()` chỉ so khớp `(merchant_id, idempotency_key)` — không hỏi "tôi có còn là chủ không?".

Demo (`LeaseFencingIT`, bản trước khi sửa; dùng `MutableClock` đẩy thời gian nên không cần thread):

| Kịch bản | Trước (đo 2026-11-13) | Sau |
|---|---|---|
| A claim → +61s → B take over → B `complete(201)` → A `complete(502)` → replay | **`502`, body của A** | `201`, body của B; `complete` của A trả `false` |
| ... → B `complete(201)` → A `abandon()` → `begin` lần nữa | **`NEW`** (dòng đã bị xóa, request chạy lần thứ ba) | `REPLAY`; `abandon` của A trả `false` |

Không có double-charge (`ux_payments_live_order` vẫn chặn), nhưng merchant nhận replay **sai**, hoặc request bị chạy lại.

## 1. Lease không phải lock

Lock: giữ tới khi nhả. Nếu chủ chết, khóa kẹt mãi. Lease: giữ **có thời hạn**, hết hạn thì người khác lấy được — giải quyết chuyện chủ chết.

Cái giá: "hết hạn" chỉ nói rằng *đồng hồ đã chạy qua mốc*. Nó **không** nói chủ cũ đã chết. Chủ cũ có thể chỉ đang:

- đứng hình vì GC pause, hoặc máy bị suspend;
- chờ một lời gọi mạng chậm;
- sống ở máy có đồng hồ lệch.

Chủ cũ không biết mình đã mất lease. Khi tỉnh lại nó làm tiếp việc dang dở: ghi kết quả. Đó là **zombie writer**. Mọi lần "kiểm tra lease còn hạn rồi mới ghi" trong application đều vô ích, vì giữa kiểm tra và ghi nó vẫn có thể đứng hình (lại là check-then-act).

## 2. Fencing token

Ý tưởng (Martin Kleppmann, "How to do distributed locking"): mỗi lần **cấp** lease kèm một **số chỉ tăng**. Chủ lease đưa số đó ra trong **mọi** lần ghi; nơi lưu trữ từ chối số cũ.

Ở đây nơi cấp lease và nơi lưu là **cùng một dòng** trong Postgres, nên chỉ cần thêm một điều kiện:

```sql
update idempotency_keys
set status = 'COMPLETED', response_status = :s, response_body = :b, lease_until = null
where merchant_id = :m and idempotency_key = :k
  and status = 'IN_PROGRESS' and lease_epoch = :e     -- "tôi còn là chủ"
```

Kiểm tra quyền sở hữu và ghi là **một câu lệnh**: database quyết, không có khe hở. Zombie đưa token cũ → khớp 0 dòng → không ghi gì. `abandon` (DELETE) cũng có đúng điều kiện đó.

- `claim` và `takeOverExpiredLease` trả về token; `begin()` đưa nó ra qua `IdempotencyOutcome.leaseEpoch()`.
- `IdempotencyFilter` giữ token trong suốt request và đưa lại khi `complete`/`abandon`.
- Token không bao giờ rời server (không nằm trong header hay body).

## 3. Token phải sống lâu hơn dòng

Bản thiết kế đầu: `lease_epoch` là counter **trong dòng** — claim đặt 1, take over thì `+ 1`. Sai ở chỗ `abandon` **xóa dòng** (TTL purge cũng vậy):

```
A claim            → epoch 1, rồi đứng hình
B take over        → epoch 2
B nhận 503, abandon → dòng bị xóa
C claim (retry)    → dòng MỚI, epoch lại là 1
A tỉnh dậy, complete(epoch 1) → khớp dòng của C
```

Đây là **ABA**: giá trị quay về như cũ dù ở giữa đã có thay đổi. Nên token lấy từ **sequence** của Postgres:

```sql
CREATE SEQUENCE idempotency_lease_epoch_seq;
ALTER TABLE idempotency_keys
    ADD COLUMN lease_epoch BIGINT NOT NULL DEFAULT nextval('idempotency_lease_epoch_seq');
```

Sequence không bao giờ lặp lại, kể cả khi transaction rollback, nên token duy nhất qua mọi "kiếp" của dòng. Test `zombieCannotTouchAFreshClaimMadeAfterTheKeyWasReleased` chính là kịch bản trên.

Phương án khác là UUID ngẫu nhiên cho mỗi lần claim: cũng đúng ở đây (vì chỉ so *bằng*, không so *lớn hơn*). Chọn sequence vì nó giữ nguyên nghĩa "số chỉ tăng", đọc log dễ biết ai đến sau.

## 4. Zombie trả lời client của nó thế nào

`complete` trả `false` → filter log WARN (`Idempotency lease lost: merchant=… key=… epoch=…`), **không lưu, không xóa**, và vẫn trả cho client của chính nó response nó đã tính. Client đó thường đã timeout từ lâu; nếu còn nghe thì nhận câu trả lời thật của request của mình.

## 5. Cái fence KHÔNG hứa

Fence bảo đảm: **response được lưu là của chủ lease hiện tại**. Nó không chọn response "tốt nhất".

Kịch bản ở tầng HTTP (`stalledCreateRequestKeepsItsOwnResponseButStoresNothing`):

1. A claim key, tx1 tạo payment `CREATED`, rồi kẹt trong lời gọi provider quá 60s.
2. B (cùng key, cùng body) take over, chạy create → đụng `ux_payments_live_order` (dòng của A) → `409 ORDER_ALREADY_EXISTS`. B **lưu** 409 đó.
3. A tỉnh dậy, hoàn tất, nhận `201` kèm `payUrl` — nhưng không lưu được.
4. Merchant retry với key đó → replay **409**.

Kết quả đúng và **tất định** (trước đây ai ghi sau thì thắng), có đúng một payment và một provider call. Nhưng merchant chỉ biết "đơn đã tồn tại", không biết payment id. Đã quyết định (2026-11-12): chấp nhận và ghi lại, không đổi body của 409.

## So với cách của Stripe

Thiết kế kiểu Stripe (Brandur, "Implementing Stripe-like idempotency keys in Postgres") dùng `locked_at` + `recovery_point`: request sau thấy khóa quá cũ thì **tiếp tục từ điểm khôi phục** thay vì chạy lại từ đầu. Mạnh hơn nhưng nặng hơn nhiều; ở đây chỉ cần "đừng để kẻ đến muộn ghi đè".

## Giới hạn đã biết

- **Không có heartbeat gia hạn lease.** Request hợp lệ lâu hơn 60s sẽ bị take over. Hiện tại timeout provider là 2s + 5s nên không chạm tới.
- **Triển khai cuốn chiếu.** Nếu bản code cũ (ghi không fence) chạy song song bản mới thì fence vô hiệu với các request rơi vào bản cũ. Ở đây chỉ có một instance.
- Nếu **lưu response thất bại** (database lỗi đúng lúc `complete`) sau khi create đã thành công, key **không** được nhả: nó giữ `IN_PROGRESS` tới khi hết lease (60s), retry trong lúc đó nhận `409 IDEMPOTENCY_IN_PROGRESS`. Cố ý: nhả key lúc này là mời request chạy lần hai.
- TTL purge không fence (xóa theo `expires_at`), chủ ý: 24h so với lease 60s.

## Tự phá thử (bài tập)

1. Bỏ `and lease_epoch = :e` trong `IdempotencyStore.complete`. Test nào trong `LeaseFencingIT` đỏ, test nào vẫn xanh? Vì sao? (Gợi ý: ở vài test, lúc zombie ghi thì dòng đã `COMPLETED`, nên điều kiện `status = 'IN_PROGRESS'` tự nó đã chặn được.)
2. Đổi `takeOverExpiredLease` thành `lease_epoch = lease_epoch + 1` và `claim` ghi cứng `lease_epoch = 1`. Chỉ **một** test đỏ. Giải thích bằng sơ đồ ở mục 3.
3. Trong `IdempotencyFilter.runAndStore`, đọc lại dòng để "kiểm tra còn là chủ không" rồi mới gọi `complete` không fence. Viết ra interleaving làm cách này vẫn sai.
