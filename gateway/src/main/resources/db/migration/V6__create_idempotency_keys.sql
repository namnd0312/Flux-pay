-- Idempotency keys make POST /api/v1/payments safe to retry. One row per
-- (merchant_id, idempotency_key): the composite primary key is the arbiter under
-- concurrency, so two identical retries racing resolve to exactly one NEW via
-- INSERT ... ON CONFLICT DO NOTHING; the loser reads this row and replays or waits.
--
-- status is IN_PROGRESS while the first request runs (lease_until bounds how long a
-- crashed worker can hold the key before another retry takes over) or COMPLETED once a
-- storable response is recorded (response_status + response_body are replayed verbatim).
-- expires_at drives the 24h TTL purge. No JPA entity: like webhook_events, this table is
-- driven by JdbcClient so it can use ON CONFLICT and a conditional lease take-over.
CREATE TABLE idempotency_keys (
    merchant_id      UUID         NOT NULL,
    idempotency_key  VARCHAR(64)  NOT NULL,
    request_hash     VARCHAR(64)  NOT NULL,
    status           VARCHAR(20)  NOT NULL,
    lease_until      TIMESTAMPTZ,
    response_status  INTEGER,
    response_body    TEXT,
    created_at       TIMESTAMPTZ  NOT NULL,
    expires_at       TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (merchant_id, idempotency_key)
);
CREATE INDEX ix_idempotency_expires ON idempotency_keys (expires_at);
