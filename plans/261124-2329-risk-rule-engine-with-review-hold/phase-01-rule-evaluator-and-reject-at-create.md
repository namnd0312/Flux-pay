---
phase: 1
title: "Rule evaluator and reject at create"
status: completed
priority: P1
dependencies: []
effort: "1 session"
---

# Phase 1: Rule evaluator and reject at create

## Overview

Build the engine core: fact model, JSON condition compiler/evaluator, `risk_rules` table, and wire `REJECT` into payment creation. Rules are read from the DB on every request in this phase on purpose; phase 2 shows why that is wrong. Learning note `10-rule-engine-facts-and-conditions.md`.

## Requirements
- Functional: a matching `REJECT` rule stops creation with `422 RISK_REJECTED` before any row or provider call. No match = existing flow unchanged.
- Non-functional: `risk` package does not import from `payment`. No new dependency. Evaluator is pure (no I/O) and unit-testable without Spring.

## Failure reproduced first
Add a hard-coded `if (amount > 50_000_000) throw` in `PaymentOrchestrator.create()`, show in Postman that changing the limit or making it per-merchant needs a rebuild and redeploy. Remove it once the engine replaces it.

## Architecture

```
PaymentOrchestrator.create()
  resolve provider
  facts = RiskFacts(amount, provider, merchantId, orderId, hourOfDay)
  decision = RiskEngine.evaluate(facts)        // phase 1: loads rules via RiskRuleStore each call
  REJECT -> throw RiskRejectedException(reasonCode)
  ALLOW  -> existing tx1 / provider / tx2
```

- `RiskFacts`: record; `asMap()` exposes only whitelisted field names.
- `RuleCompiler`: parses condition JSON into a `Condition` tree (`All`, `Any`, `Leaf`). Rejects unknown field, unknown op, wrong value type (`in` needs an array; ordering ops need a number), empty `all`/`any`, nesting deeper than 5.
- `Condition.test(Map<String,Object> facts)`: numbers compared as `long`/`BigDecimal`, strings by equality, no implicit coercion between types.
- `RiskEngine.evaluate`: iterate compiled rules in `(priority, id)` order, keep those where `merchant_id` is null or equals the fact's merchant, first match wins.
- `RiskDecision`: record `(outcome, ruleId, reasonCode)`; `Outcome` enum `ALLOW, REJECT` (REVIEW added in phase 3).

## Related Code Files
- Create: `gateway/src/main/resources/db/migration/V9__create_risk_rules.sql` (table, `CHECK (action IN ('REJECT'))`, index on `(enabled, priority)`, seed one global rule `amount gt 50000000 -> REJECT AMOUNT_OVER_LIMIT`, priority 100)
- Create in `gateway/src/main/java/com/fluxpay/gateway/risk/`: `RiskFacts.java`, `Condition.java`, `RuleCompiler.java`, `InvalidRuleException.java`, `CompiledRule.java`, `RiskRuleStore.java` (`JdbcClient`, reads `condition::text`), `RiskEngine.java`, `RiskDecision.java`, `RiskRejectedException.java`
- Modify: `payment/PaymentOrchestrator.java` (build facts, call engine after provider resolve, before tx1)
- Modify: `common/ProblemDetailsHandler.java` (`RiskRejectedException` -> 422 `RISK_REJECTED`, detail carries the reason code)
- Modify: `gateway/src/main/resources/application.yml` (`fluxpay.risk.zone: Asia/Ho_Chi_Minh`)
- Create tests: `risk/RuleCompilerTest.java`, `risk/ConditionEvaluationTest.java`, `risk/RiskEngineTest.java`, `payment/RiskRejectAtCreateIT.java`
- Create: `docs/learning/10-rule-engine-facts-and-conditions.md`; Postman folder "Phase 10 - Risk rules"

## Implementation Steps
1. Reproduce the hard-coded limit failure; capture it for the note.
2. Write `V9`; keep `reason_code` at `VARCHAR(40)` to match `payment_transitions.reason_code`.
3. Write `RuleCompilerTest` and `ConditionEvaluationTest` first (valid tree, each operator, each rejection case), then `RuleCompiler` and `Condition`.
4. `RiskRuleStore.findEnabled()` + `RiskEngine`; a rule that fails to compile is logged at ERROR and skipped.
5. Wire into `PaymentOrchestrator.create()`; add the exception mapping. Use the project's existing JSON mapper (check `CommonConfig`), do not add another.
6. `RiskRejectAtCreateIT`: over-limit -> 422, zero rows in `payments`, `CountingFakeProvider` count 0; under-limit -> 201; merchant rule with lower priority number beats global; malformed rule row -> request still answers 201/422, never 500; same idempotency key replays the 422.
7. Remove the hard-coded `if`. Run `./mvnw verify`.
8. Write note 10: fact model, why a whitelist and not SpEL, first-match vs collect-all, fail-open on a bad rule.

## Success Criteria
- [x] Acceptance 1, 3, 5 pass in `RiskRejectAtCreateIT`
- [x] Evaluator unit tests cover every operator and every compile rejection
- [x] No class in `risk` imports `com.fluxpay.gateway.payment`
- [x] All increment 1 and 2 tests green
- [x] Note 10 written with a "break it yourself" exercise

## Risk Assessment
- Seed rule blocks existing tests or JMeter plans: they use 1,000,000, threshold is 50,000,000; re-check after writing `V9`.
- JSON library version (Boot 4): follow the mapper already used in the codebase rather than assuming package names.
- Number handling: JSON `50000000` vs `5e7` vs string `"50000000"`; compiler rejects non-integral and string values for ordering ops on `amount`.
