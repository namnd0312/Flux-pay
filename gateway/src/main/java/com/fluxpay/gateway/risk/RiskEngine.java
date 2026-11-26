package com.fluxpay.gateway.risk;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

/**
 * Decides whether a payment may be created. Rules are tried in {@code (priority, id)} order,
 * skipping rules that belong to another merchant; the first rule whose condition matches
 * decides, and no match means {@link RiskDecision#allow()}.
 *
 * <p>Evaluation touches memory only: the rules come from {@link RiskRuleCache}, which owns
 * loading, compiling and what happens to a rule that does not compile.
 */
@Service
public class RiskEngine {

    private final RiskRuleCache cache;

    public RiskEngine(RiskRuleCache cache) {
        this.cache = cache;
    }

    public RiskDecision evaluate(RiskFacts facts) {
        return decide(cache.current(), facts);
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
}
