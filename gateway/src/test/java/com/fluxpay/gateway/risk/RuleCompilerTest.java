package com.fluxpay.gateway.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.fluxpay.gateway.risk.Condition.All;
import com.fluxpay.gateway.risk.Condition.Any;
import com.fluxpay.gateway.risk.Condition.Leaf;
import com.fluxpay.gateway.risk.Condition.Op;

import tools.jackson.databind.json.JsonMapper;

/**
 * The compiler accepts exactly the rule grammar and refuses everything else with a message
 * that names the problem. Every refusal here is a rule that would otherwise mean something
 * different from what its author typed.
 */
class RuleCompilerTest {

    private final RuleCompiler compiler = new RuleCompiler(JsonMapper.builder().build());

    @Test
    void compilesALeaf() {
        assertThat(compiler.compile("{\"field\":\"amount\",\"op\":\"gt\",\"value\":50000000}"))
                .isEqualTo(new Leaf("amount", Op.GT, 50_000_000L));
    }

    @Test
    void compilesANestedTree() {
        Condition tree = compiler.compile("""
                {"all":[
                  {"field":"provider","op":"eq","value":"MOCK"},
                  {"any":[
                    {"field":"amount","op":"gte","value":100},
                    {"field":"hourOfDay","op":"in","value":[0,1,2]}
                  ]}
                ]}
                """);
        assertThat(tree).isEqualTo(new All(List.of(
                new Leaf("provider", Op.EQ, "MOCK"),
                new Any(List.of(
                        new Leaf("amount", Op.GTE, 100L),
                        new Leaf("hourOfDay", Op.IN, Set.of(0L, 1L, 2L)))))));
    }

    @ParameterizedTest
    @CsvSource({"eq,EQ", "ne,NE", "gt,GT", "gte,GTE", "lt,LT", "lte,LTE"})
    void acceptsEveryComparisonOperatorOnANumberField(String json, Op expected) {
        assertThat(compiler.compile("{\"field\":\"amount\",\"op\":\"%s\",\"value\":1}".formatted(json)))
                .isEqualTo(new Leaf("amount", expected, 1L));
    }

    @Test
    void acceptsInOnATextField() {
        assertThat(compiler.compile("{\"field\":\"provider\",\"op\":\"in\",\"value\":[\"MOCK\",\"VNPAY\"]}"))
                .isEqualTo(new Leaf("provider", Op.IN, Set.of("MOCK", "VNPAY")));
    }

    @Test
    void acceptsNestingAtTheDepthLimit() {
        assertThat(compiler.compile(nested(RuleCompiler.MAX_DEPTH))).isInstanceOf(All.class);
    }

    @Test
    void refusesNestingBeyondTheDepthLimit() {
        assertRefused(nested(RuleCompiler.MAX_DEPTH + 1), "nested deeper");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not json", "{\"field\":", "[1,2"})
    void refusesTextThatIsNotJson(String json) {
        assertThatThrownBy(() -> compiler.compile(json)).isInstanceOf(InvalidRuleException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"amount > 5\"", "42", "[]", "null", "true"})
    void refusesAConditionThatIsNotAnObject(String json) {
        assertRefused(json, "must be a JSON object");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"field\":\"amount\",\"op\":\"gt\"}",
            "{\"field\":\"amount\",\"op\":\"gt\",\"value\":1,\"extra\":true}",
            "{\"all\":[{\"field\":\"amount\",\"op\":\"gt\",\"value\":1}],\"any\":[]}",
            "{\"not\":{\"field\":\"amount\",\"op\":\"gt\",\"value\":1}}"})
    void refusesAnObjectWithTheWrongKeys(String json) {
        assertRefused(json, "must have exactly");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"all\":[]}", "{\"any\":[]}", "{\"all\":{}}", "{\"any\":\"x\"}"})
    void refusesAnEmptyOrNonArrayGroup(String json) {
        assertRefused(json, "must be a non-empty array");
    }

    @Test
    void refusesAnUnknownField() {
        assertRefused("{\"field\":\"customerEmail\",\"op\":\"eq\",\"value\":\"a\"}", "unknown field");
    }

    @ParameterizedTest
    @ValueSource(strings = {"between", "GT", "==", ""})
    void refusesAnUnknownOperator(String op) {
        assertRefused("{\"field\":\"amount\",\"op\":\"%s\",\"value\":1}".formatted(op), "unknown operator");
    }

    @Test
    void refusesFieldOrOperatorThatIsNotAString() {
        assertRefused("{\"field\":1,\"op\":\"eq\",\"value\":1}", "must be strings");
        assertRefused("{\"field\":\"amount\",\"op\":null,\"value\":1}", "must be strings");
    }

    @Test
    void refusesAnOrderingOperatorOnATextField() {
        assertRefused("{\"field\":\"provider\",\"op\":\"gt\",\"value\":\"A\"}", "needs a number field");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"50000000\"", "5e7", "50000000.0", "1.5", "true", "null", "[1]", "{}",
            "99999999999999999999"})
    void refusesANumberFieldValueThatIsNotAWholeNumber(String value) {
        assertRefused("{\"field\":\"amount\",\"op\":\"gt\",\"value\":%s}".formatted(value), "whole-number");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "true", "null", "[\"MOCK\"]"})
    void refusesATextFieldValueThatIsNotAString(String value) {
        assertRefused("{\"field\":\"provider\",\"op\":\"eq\",\"value\":%s}".formatted(value), "string value");
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "1", "\"MOCK\""})
    void refusesInWithoutANonEmptyArray(String value) {
        assertRefused("{\"field\":\"provider\",\"op\":\"in\",\"value\":%s}".formatted(value), "non-empty array");
    }

    @Test
    void refusesInWithAnElementOfTheWrongType() {
        assertRefused("{\"field\":\"amount\",\"op\":\"in\",\"value\":[1,\"2\"]}", "whole-number");
    }

    private void assertRefused(String json, String messagePart) {
        assertThatThrownBy(() -> compiler.compile(json))
                .isInstanceOf(InvalidRuleException.class)
                .hasMessageContaining(messagePart);
    }

    /** A chain of {@code all} nodes with a leaf at the bottom, {@code depth} levels in total. */
    private static String nested(int depth) {
        String json = "{\"field\":\"amount\",\"op\":\"gt\",\"value\":1}";
        for (int i = 1; i < depth; i++) {
            json = "{\"all\":[" + json + "]}";
        }
        return json;
    }
}
