-- A second action: REVIEW lets the payment exist but holds it (status UNDER_REVIEW) until an
-- operator decides. The CHECK constraint from V9 was created inline, so PostgreSQL named it
-- risk_rules_action_check.
ALTER TABLE risk_rules DROP CONSTRAINT risk_rules_action_check;
ALTER TABLE risk_rules ADD CONSTRAINT risk_rules_action_check CHECK (action IN ('REJECT', 'REVIEW'));

-- Seed: hold anything above 20,000,000. Priority 200 is tried after the 50,000,000 reject
-- rule (priority 100), so a payment above 50,000,000 is still rejected, not held.
INSERT INTO risk_rules (name, merchant_id, priority, condition, action, reason_code)
VALUES ('global-review-high-amount', NULL, 200,
        '{"field":"amount","op":"gt","value":20000000}', 'REVIEW', 'HIGH_AMOUNT');
