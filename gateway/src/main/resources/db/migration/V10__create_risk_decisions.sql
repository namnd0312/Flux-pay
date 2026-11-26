-- Append-only record of what the risk rules decided for each create request that was evaluated:
-- which rule matched (if any) and the exact facts it saw. This is what answers "why was this
-- payment blocked?" after the rule has been edited or deleted.
--
-- payment_id is set for an allowed request (the row is written in the same transaction as the
-- payment insert) and null for a rejected one, which never gets a payment.
-- matched_rule_id has no foreign key on purpose: deleting a rule must not delete or block history.
-- facts holds only the fields a rule may test (see RiskFacts). It must never hold secrets,
-- signatures or request headers.
CREATE TABLE risk_decisions (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    merchant_id     UUID         NOT NULL,
    order_id        VARCHAR(100) NOT NULL,
    payment_id      UUID,
    decision        VARCHAR(20)  NOT NULL,
    matched_rule_id BIGINT,
    reason_code     VARCHAR(40),
    facts           JSONB        NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_risk_decisions_payment ON risk_decisions (payment_id);
CREATE INDEX ix_risk_decisions_merchant_created ON risk_decisions (merchant_id, created_at);
