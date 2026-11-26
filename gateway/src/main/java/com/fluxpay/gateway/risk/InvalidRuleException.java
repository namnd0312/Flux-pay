package com.fluxpay.gateway.risk;

/** A rule condition that breaks the rule grammar. The message says which part and why. */
public class InvalidRuleException extends RuntimeException {

    public InvalidRuleException(String message) {
        super(message);
    }
}
