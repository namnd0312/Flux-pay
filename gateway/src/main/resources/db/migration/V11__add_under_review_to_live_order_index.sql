-- UNDER_REVIEW is a new live state: a payment held for review can still become payable, so it
-- must occupy its order just like CREATED, PENDING and SUCCEEDED do. The index predicate lists
-- the live states by name, and a partial index's predicate cannot be altered, so the index is
-- dropped and created again.
--
-- Flyway runs this file in one transaction: there is no moment at which the guard is missing.
-- The price is that both statements hold a lock that blocks writes to payments while the index
-- is built. That is instant at this table size; on a large table it would be done differently
-- (build the new index CONCURRENTLY under another name, then drop the old one).
DROP INDEX ux_payments_live_order;
CREATE UNIQUE INDEX ux_payments_live_order
    ON payments (merchant_id, order_id)
    WHERE status IN ('CREATED', 'UNDER_REVIEW', 'PENDING', 'SUCCEEDED');
