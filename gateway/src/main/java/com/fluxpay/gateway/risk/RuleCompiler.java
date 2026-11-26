package com.fluxpay.gateway.risk;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.fluxpay.gateway.risk.Condition.Op;
import com.fluxpay.gateway.risk.RiskFacts.FieldType;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns a rule's condition JSON into a {@link Condition} tree, or refuses it with an
 * {@link InvalidRuleException}. All checking happens here, once, when the rule is loaded, so
 * evaluation can assume a well-formed tree.
 *
 * <p>Grammar:
 * <pre>
 *   node := {"all":[node, ...]} | {"any":[node, ...]} | leaf
 *   leaf := {"field": name, "op": operator, "value": literal}
 * </pre>
 * Refused: an unknown field or operator, any extra or missing key, an empty {@code all}/{@code any},
 * nesting deeper than {@link #MAX_DEPTH}, a value whose type differs from the field's type,
 * an ordering operator on a text field, and {@code in} without a non-empty array.
 *
 * <p>A number must be a JSON integer: {@code 50000000} is accepted, while {@code 5e7},
 * {@code 50000000.0} and {@code "50000000"} are refused rather than quietly converted. A rule
 * that means something other than what its author typed is worse than a rule that is skipped.
 */
@Component
public class RuleCompiler {

    static final int MAX_DEPTH = 5;

    private static final Set<String> LEAF_KEYS = Set.of("field", "op", "value");

    private final ObjectMapper objectMapper;

    public RuleCompiler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Condition compile(String conditionJson) {
        JsonNode root;
        try {
            root = objectMapper.readTree(conditionJson);
        } catch (JacksonException ex) {
            throw new InvalidRuleException("condition is not valid JSON");
        }
        if (root == null) {
            throw new InvalidRuleException("condition is empty");
        }
        return node(root, 1);
    }

    private Condition node(JsonNode node, int depth) {
        if (depth > MAX_DEPTH) {
            throw new InvalidRuleException("condition is nested deeper than " + MAX_DEPTH + " levels");
        }
        if (!node.isObject()) {
            throw new InvalidRuleException("a condition must be a JSON object");
        }
        Set<String> keys = new LinkedHashSet<>(node.propertyNames());
        if (keys.equals(Set.of("all"))) {
            return new Condition.All(children(node.get("all"), "all", depth));
        }
        if (keys.equals(Set.of("any"))) {
            return new Condition.Any(children(node.get("any"), "any", depth));
        }
        if (keys.equals(LEAF_KEYS)) {
            return leaf(node);
        }
        throw new InvalidRuleException(
                "a condition must have exactly the key \"all\", the key \"any\", or the keys field/op/value; got "
                        + keys);
    }

    private List<Condition> children(JsonNode array, String key, int depth) {
        if (!array.isArray() || array.isEmpty()) {
            throw new InvalidRuleException("\"" + key + "\" must be a non-empty array");
        }
        List<Condition> children = new ArrayList<>();
        for (JsonNode child : array) {
            children.add(node(child, depth + 1));
        }
        return List.copyOf(children);
    }

    private Condition leaf(JsonNode node) {
        JsonNode fieldNode = node.get("field");
        JsonNode opNode = node.get("op");
        if (!fieldNode.isString() || !opNode.isString()) {
            throw new InvalidRuleException("\"field\" and \"op\" must be strings");
        }
        String field = fieldNode.stringValue();
        FieldType type = RiskFacts.FIELD_TYPES.get(field);
        if (type == null) {
            throw new InvalidRuleException("unknown field \"" + field + "\"");
        }
        Op op = op(opNode.stringValue());
        if (op.isOrdering() && type != FieldType.NUMBER) {
            throw new InvalidRuleException("operator \"" + opNode.stringValue() + "\" needs a number field, \""
                    + field + "\" is text");
        }
        JsonNode valueNode = node.get("value");
        if (op != Op.IN) {
            return new Condition.Leaf(field, op, literal(valueNode, type, field));
        }
        if (!valueNode.isArray() || valueNode.isEmpty()) {
            throw new InvalidRuleException("operator \"in\" needs a non-empty array value");
        }
        Set<Object> values = new LinkedHashSet<>();
        for (JsonNode element : valueNode) {
            values.add(literal(element, type, field));
        }
        return new Condition.Leaf(field, op, Set.copyOf(values));
    }

    private static Op op(String name) {
        // Operators are lower case in rule JSON; "GT" is refused so one rule has one spelling.
        if (name.equals(name.toLowerCase(Locale.ROOT))) {
            try {
                return Op.valueOf(name.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                // falls through to the error below
            }
        }
        throw new InvalidRuleException("unknown operator \"" + name + "\"");
    }

    private static Object literal(JsonNode value, FieldType type, String field) {
        if (type == FieldType.NUMBER) {
            if (!value.isIntegralNumber() || !value.canConvertToLong()) {
                throw new InvalidRuleException("field \"" + field + "\" needs a whole-number value");
            }
            return value.longValue();
        }
        if (!value.isString()) {
            throw new InvalidRuleException("field \"" + field + "\" needs a string value");
        }
        return value.stringValue();
    }
}
