package com.fluxpay.gateway.risk;

import java.util.UUID;

/**
 * A rule row whose condition passed {@link RuleCompiler}. {@code merchantId} null means the
 * rule applies to every merchant. {@code outcome} is what the engine answers when the
 * condition matches.
 */
public record CompiledRule(long id, UUID merchantId, int priority, Condition condition,
        RiskDecision.Outcome outcome, String reasonCode) {

    boolean appliesTo(UUID merchant) {
        return merchantId == null || merchantId.equals(merchant);
    }
}
