package com.fluxpay.gateway.risk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import tools.jackson.databind.json.JsonMapper;

/**
 * Evaluates compiled conditions against one fixed set of facts. Each row is a condition as a
 * rule author would write it and the answer it must give.
 */
class ConditionEvaluationTest {

    private static final UUID MERCHANT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /** amount 1,000,000 through MOCK for order ORD-1 at 23h. */
    private static final Map<String, Object> FACTS =
            new RiskFacts(1_000_000, "MOCK", MERCHANT, "ORD-1", 23).asMap();

    private final RuleCompiler compiler = new RuleCompiler(JsonMapper.builder().build());

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "{'field':'amount','op':'eq','value':1000000}  | true",
            "{'field':'amount','op':'eq','value':1000001}  | false",
            "{'field':'amount','op':'ne','value':1000001}  | true",
            "{'field':'amount','op':'ne','value':1000000}  | false",
            "{'field':'amount','op':'gt','value':999999}   | true",
            "{'field':'amount','op':'gt','value':1000000}  | false",
            "{'field':'amount','op':'gte','value':1000000} | true",
            "{'field':'amount','op':'gte','value':1000001} | false",
            "{'field':'amount','op':'lt','value':1000001}  | true",
            "{'field':'amount','op':'lt','value':1000000}  | false",
            "{'field':'amount','op':'lte','value':1000000} | true",
            "{'field':'amount','op':'lte','value':999999}  | false",
            "{'field':'amount','op':'in','value':[5,1000000]} | true",
            "{'field':'amount','op':'in','value':[5,6]}       | false",
            "{'field':'hourOfDay','op':'gte','value':22}   | true",
            "{'field':'hourOfDay','op':'lt','value':6}     | false",
            "{'field':'provider','op':'eq','value':'MOCK'} | true",
            "{'field':'provider','op':'eq','value':'mock'} | false",
            "{'field':'provider','op':'ne','value':'VNPAY'} | true",
            "{'field':'provider','op':'in','value':['VNPAY','MOCK']} | true",
            "{'field':'provider','op':'in','value':['VNPAY']}        | false",
            "{'field':'orderId','op':'eq','value':'ORD-1'} | true",
            "{'field':'merchantId','op':'eq','value':'00000000-0000-0000-0000-000000000001'} | true",
            "{'field':'merchantId','op':'eq','value':'00000000-0000-0000-0000-000000000002'} | false",
            "{'all':[{'field':'amount','op':'gt','value':1},{'field':'provider','op':'eq','value':'MOCK'}]}  | true",
            "{'all':[{'field':'amount','op':'gt','value':1},{'field':'provider','op':'eq','value':'VNPAY'}]} | false",
            "{'any':[{'field':'amount','op':'lt','value':1},{'field':'provider','op':'eq','value':'MOCK'}]}  | true",
            "{'any':[{'field':'amount','op':'lt','value':1},{'field':'provider','op':'eq','value':'VNPAY'}]} | false",
            "{'all':[{'field':'hourOfDay','op':'gte','value':22},{'any':[{'field':'amount','op':'gt','value':5000000},{'field':'orderId','op':'eq','value':'ORD-1'}]}]} | true"})
    void evaluates(String condition, boolean expected) {
        assertThat(compiler.compile(condition.replace('\'', '"')).test(FACTS)).isEqualTo(expected);
    }
}
