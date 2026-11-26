---
phase: 2
title: "Rule cache refresh and decision audit"
status: completed
priority: P1
dependencies: [1]
effort: "1 session"
---

# Phase 2: Rule cache refresh and decision audit

## Overview

Stop reading rules per request: compile once, cache, refresh on a schedule. Record every decision so "why was this payment blocked" has an answer. Learning note `11-rule-cache-and-decision-audit.md`.

## Requirements
- Functional: rule changes made by SQL apply within one refresh interval without restart; each evaluation leaves one `risk_decisions` row.
- Non-functional: evaluation does no DB read; a failed refresh never empties the active rule set; the audit row is atomic with the payment insert.

## Failure reproduced first
1. A rejected request leaves no trace: nobody can say which rule fired or with which facts.
2. Count SQL statements per create (datasource statistics or statement logging) to show the extra `SELECT` on every request.

## Architecture
- `RiskRuleCache`: `volatile List<CompiledRule>`; `refresh()` loads + compiles, swaps the reference only on success; on exception logs and keeps the previous list. Initial load at startup; a failure there stops the application.
- `@Scheduled(fixedDelayString = "${fluxpay.risk.refresh-interval:10s}")`, guarded by the existing `fluxpay.scheduling.enabled` switch (off in tests; tests call `refresh()` directly).
- `RiskEngine` reads from the cache only.
- `RiskDecisionStore.record(decision, facts, paymentId)` via `JdbcClient`, `facts` stored as JSONB.
- Orchestrator: REJECT -> record in its own transaction, then throw. ALLOW -> record inside tx1 with the payment id, so a rolled-back insert (duplicate order) leaves no decision row.

## Related Code Files
- Create: `gateway/src/main/resources/db/migration/V10__create_risk_decisions.sql` (`id, merchant_id, order_id, payment_id NULL, decision, matched_rule_id NULL, reason_code NULL, facts JSONB, created_at`; indexes on `payment_id` and `(merchant_id, created_at)`; no FK to `risk_rules` so a rule can be deleted without losing history)
- Create: `risk/RiskRuleCache.java`, `risk/RiskDecisionStore.java`
- Modify: `risk/RiskEngine.java`, `payment/PaymentOrchestrator.java`, `application.yml` (`fluxpay.risk.refresh-interval`)
- Create tests: `risk/RiskRuleCacheIT.java`, `risk/RiskDecisionAuditIT.java`
- Create: `docs/learning/11-rule-cache-and-decision-audit.md`; extend the Postman folder

## Implementation Steps
1. Reproduce both failures; record the statement count before.
2. `V10`.
3. `RiskRuleCache` + wire `RiskEngine` to it; remove the per-request load.
4. `RiskRuleCacheIT`: update a threshold by SQL, call `refresh()`, behaviour changes; break the table read (or insert an uncompilable rule) and confirm the previous good rules still apply; disabled rule is ignored.
5. `RiskDecisionStore` + orchestrator wiring.
6. `RiskDecisionAuditIT`: ALLOW row carries `payment_id`; REJECT row has null `payment_id` and the rule id; duplicate-order 409 leaves no ALLOW row; facts JSON holds the five fact fields and nothing else (no secrets, no headers).
7. Re-measure statement count; `./mvnw verify`.
8. Note 11: cache staleness window, atomic swap, why audit sits in the payment transaction, what the audit table must never contain.

## Success Criteria
- [x] Acceptance 2 and 4 pass
- [x] No rule `SELECT` on the request path (statement count back to the pre-phase-1 number plus one audit insert)
- [x] Failed refresh keeps the last good rule set (test)
- [x] `./mvnw verify` green; note 11 written

## Risk Assessment
- Tests in other classes share the Spring context and the cache: each IT that inserts rules must clean `risk_rules` back to the seed and call `refresh()` in teardown.
- Startup order: initial load must run after Flyway; use the same startup hook style as `SecretCipherStartupCheck`.
- REJECT audit in a separate transaction can commit while the response fails; accepted (audit says a rejection was decided).
