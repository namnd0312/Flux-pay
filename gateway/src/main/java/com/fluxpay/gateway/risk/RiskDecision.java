package com.fluxpay.gateway.risk;

/**
 * The answer of the rule engine for one request. {@code ruleId} and {@code reasonCode} are
 * null for {@link Outcome#ALLOW}, which means no rule matched.
 */
public record RiskDecision(Outcome outcome, Long ruleId, String reasonCode) {

    public enum Outcome { ALLOW, REJECT }

    public static RiskDecision allow() {
        return new RiskDecision(Outcome.ALLOW, null, null);
    }
}
