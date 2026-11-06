-- Payments are the central entity: one row per attempt to collect money for an order.
-- No unique (merchant_id, order_id) yet; the duplicate-order guard is a partial unique
-- index added in phase 6, so the duplicate-order demo stays real until then.
-- version is the optimistic-lock column used by increment 2 (present now so the schema
-- does not change later).
CREATE TABLE payments (
    id            UUID PRIMARY KEY,
    merchant_id   UUID         NOT NULL,
    order_id      VARCHAR(100) NOT NULL,
    amount        BIGINT       NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    provider_code VARCHAR(40),
    provider_ref  VARCHAR(100),
    pay_url       VARCHAR(500),
    expires_at    TIMESTAMPTZ  NOT NULL,
    version       BIGINT       NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_payments_status_expires ON payments (status, expires_at);

-- Append-only audit ledger: every status change writes exactly one row here.
-- reason_code is a short stable token (never free text) so later reconciliation can
-- group and reason about transitions. from_status is null for the very first row.
CREATE TABLE payment_transitions (
    id          UUID PRIMARY KEY,
    payment_id  UUID         NOT NULL REFERENCES payments (id),
    from_status VARCHAR(20),
    to_status   VARCHAR(20)  NOT NULL,
    reason_code VARCHAR(40)  NOT NULL,
    source      VARCHAR(20)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_transitions_payment ON payment_transitions (payment_id, created_at);
