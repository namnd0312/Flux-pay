-- Risk rules are data, not code: one row is one rule, evaluated before a payment is created.
-- Changing a limit is an UPDATE here, not a rebuild and redeploy.
--
-- condition is a JSON tree checked by RuleCompiler when the rule is loaded:
--   node: {"all":[...]} or {"any":[...]}      leaf: {"field":"amount","op":"gt","value":50000000}
-- JSONB only guarantees the text is valid JSON. A row whose tree breaks the grammar (unknown
-- field, wrong value type, ...) is skipped with an ERROR log; it never fails a request.
--
-- Rules are tried in (priority, id) order and the first match decides. merchant_id null means
-- the rule applies to every merchant; a merchant rule only wins through a lower priority number.
-- reason_code is the stable token returned to the merchant, same width as
-- payment_transitions.reason_code.
CREATE TABLE risk_rules (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    merchant_id UUID,
    priority    INT          NOT NULL,
    condition   JSONB        NOT NULL,
    action      VARCHAR(20)  NOT NULL CHECK (action IN ('REJECT')),
    reason_code VARCHAR(40)  NOT NULL,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_risk_rules_enabled_priority ON risk_rules (enabled, priority);

-- Seed: no single payment above 50,000,000. Existing tests and load tests use 1,000,000.
INSERT INTO risk_rules (name, merchant_id, priority, condition, action, reason_code)
VALUES ('global-amount-limit', NULL, 100,
        '{"field":"amount","op":"gt","value":50000000}', 'REJECT', 'AMOUNT_OVER_LIMIT');
