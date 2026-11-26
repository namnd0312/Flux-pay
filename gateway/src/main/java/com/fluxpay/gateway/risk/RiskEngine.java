package com.fluxpay.gateway.risk;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fluxpay.gateway.risk.RiskRuleStore.RuleRow;

/**
 * Decides whether a payment may be created. Rules are tried in {@code (priority, id)} order,
 * skipping rules that belong to another merchant; the first rule whose condition matches
 * decides, and no match means {@link RiskDecision#allow()}.
 *
 * <p>A rule row that does not compile is logged at ERROR and skipped. That is fail-open for
 * that one rule: a typo in a rule lets the payments it was meant to stop go through, but it
 * can never turn every create request into a 500.
 *
 * <p>The rules are read from the database and compiled on every call.
 */
@Service
public class RiskEngine {

    private static final Logger log = LoggerFactory.getLogger(RiskEngine.class);

    private final RiskRuleStore store;
    private final RuleCompiler compiler;

    public RiskEngine(RiskRuleStore store, RuleCompiler compiler) {
        this.store = store;
        this.compiler = compiler;
    }

    public RiskDecision evaluate(RiskFacts facts) {
        return decide(compile(store.findEnabled()), facts);
    }

    /** Compiles rows in the given order, leaving out the ones that are not valid rules. */
    List<CompiledRule> compile(List<RuleRow> rows) {
        List<CompiledRule> rules = new ArrayList<>();
        for (RuleRow row : rows) {
            try {
                rules.add(new CompiledRule(row.id(), row.merchantId(), row.priority(),
                        compiler.compile(row.condition()), outcome(row.action()), row.reasonCode()));
            } catch (InvalidRuleException ex) {
                log.error("Risk rule {} is invalid and was skipped: {}", row.id(), ex.getMessage());
            }
        }
        return rules;
    }

    /** First match wins. {@code rules} must already be in evaluation order. */
    static RiskDecision decide(List<CompiledRule> rules, RiskFacts facts) {
        Map<String, Object> values = facts.asMap();
        for (CompiledRule rule : rules) {
            if (rule.appliesTo(facts.merchantId()) && rule.condition().test(values)) {
                return new RiskDecision(rule.outcome(), rule.id(), rule.reasonCode());
            }
        }
        return RiskDecision.allow();
    }

    private static RiskDecision.Outcome outcome(String action) {
        if ("REJECT".equals(action)) {
            return RiskDecision.Outcome.REJECT;
        }
        throw new InvalidRuleException("unknown action \"" + action + "\"");
    }
}
