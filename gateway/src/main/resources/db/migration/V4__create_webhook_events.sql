-- Append-only ledger of inbound provider webhooks (IPNs). The unique (provider_code,
-- event_id) is the ONLY arbiter of duplicates: the ingestion path inserts with
-- ON CONFLICT DO NOTHING, and a lost insert means "already seen" (duplicate_count++).
-- raw_body is stored verbatim so the exact signed bytes are auditable. payment_id is
-- null when the event referenced no known payment (UNKNOWN_PAYMENT rejection).
CREATE TABLE webhook_events (
    id                UUID PRIMARY KEY,
    provider_code     VARCHAR(40)  NOT NULL,
    event_id          VARCHAR(100) NOT NULL,
    payment_id        UUID,
    raw_body          TEXT         NOT NULL,
    processing_status VARCHAR(20)  NOT NULL,
    reject_reason     VARCHAR(40),
    duplicate_count   INTEGER      NOT NULL DEFAULT 0,
    received_at       TIMESTAMPTZ  NOT NULL,
    processed_at      TIMESTAMPTZ,
    CONSTRAINT ux_webhook_events_provider_event UNIQUE (provider_code, event_id)
);
