-- Fencing token for the idempotency lease.
--
-- A lease that only expires is not safe: a worker can stall past lease_until (GC pause,
-- slow I/O) without being dead, another request takes the lease over, and then the first
-- worker wakes up and writes. Without a guard that late write overwrites, or deletes, the
-- new owner's row.
--
-- lease_epoch is the guard: every acquisition (first claim and each take-over) gets a new
-- value, the owner keeps it, and complete/abandon only succeed while the row still carries
-- that value. A stale owner's write then matches zero rows.
--
-- The value comes from a sequence, not from "lease_epoch + 1" in the row: abandoning a key
-- deletes its row, so an in-row counter would restart at 1 on the next claim and a stale
-- owner still holding "1" would match a stranger's lease. A sequence never repeats, so the
-- token stays unique across row lifetimes.
CREATE SEQUENCE idempotency_lease_epoch_seq;

ALTER TABLE idempotency_keys
    ADD COLUMN lease_epoch BIGINT NOT NULL DEFAULT nextval('idempotency_lease_epoch_seq');

-- Tie the sequence to the column so it is dropped together with it.
ALTER SEQUENCE idempotency_lease_epoch_seq OWNED BY idempotency_keys.lease_epoch;
