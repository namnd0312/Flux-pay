-- Business-level second guard against duplicate charges: at most one LIVE payment per
-- (merchant_id, order_id). "Live" = CREATED, PENDING or SUCCEEDED. The partial predicate
-- is deliberate: a FAILED, EXPIRED or CANCELLED attempt must NOT block the merchant from
-- creating the order again (e.g. retry after a 502), so terminal-but-not-successful rows
-- are excluded from the uniqueness. A violation surfaces as a DataIntegrityViolationException
-- in the create flow and is mapped to 409 ORDER_ALREADY_EXISTS.
--
-- This complements the idempotency key (same key -> same answer); the index covers the
-- different-key-same-order case the key cannot see.
CREATE UNIQUE INDEX ux_payments_live_order
    ON payments (merchant_id, order_id)
    WHERE status IN ('CREATED', 'PENDING', 'SUCCEEDED');
