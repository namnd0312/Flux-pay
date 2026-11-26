package com.fluxpay.gateway.risk;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A compiled rule condition: a small tree that answers yes or no for a set of facts. It is
 * pure (no I/O, no clock, no state), so one tree can be evaluated by many requests at once.
 *
 * <p>Trees are only built by {@link RuleCompiler}, which has already checked that every leaf
 * names a known field and carries a value of that field's type. Evaluation therefore never
 * converts between types: numbers are compared as {@code Long}, strings by equality.
 */
public sealed interface Condition {

    boolean test(Map<String, Object> facts);

    /** True when every child is true. */
    record All(List<Condition> children) implements Condition {
        @Override
        public boolean test(Map<String, Object> facts) {
            return children.stream().allMatch(c -> c.test(facts));
        }
    }

    /** True when at least one child is true. */
    record Any(List<Condition> children) implements Condition {
        @Override
        public boolean test(Map<String, Object> facts) {
            return children.stream().anyMatch(c -> c.test(facts));
        }
    }

    /**
     * One comparison of a fact against a literal. {@code value} is a {@code Long} or a
     * {@code String}, or a {@code Set} of those for {@link Op#IN}.
     */
    record Leaf(String field, Op op, Object value) implements Condition {
        @Override
        public boolean test(Map<String, Object> facts) {
            Object fact = facts.get(field);
            if (fact == null) {
                return false; // a fact that is absent matches nothing
            }
            return switch (op) {
                case EQ -> fact.equals(value);
                case NE -> !fact.equals(value);
                case IN -> ((Set<?>) value).contains(fact);
                case GT -> compare(fact) > 0;
                case GTE -> compare(fact) >= 0;
                case LT -> compare(fact) < 0;
                case LTE -> compare(fact) <= 0;
            };
        }

        private int compare(Object fact) {
            return Long.compare((Long) fact, (Long) value);
        }
    }

    /** The operators of the rule language, written in lower case in a rule's JSON. */
    enum Op {
        EQ, NE, GT, GTE, LT, LTE, IN;

        /** Ordering operators only make sense for numbers. */
        boolean isOrdering() {
            return this == GT || this == GTE || this == LT || this == LTE;
        }
    }
}
