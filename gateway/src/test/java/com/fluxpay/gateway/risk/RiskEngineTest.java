package com.fluxpay.gateway.risk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fluxpay.gateway.risk.RiskDecision.Outcome;
import com.fluxpay.gateway.risk.RiskRuleStore.RuleRow;

import tools.jackson.databind.json.JsonMapper;

/**
 * Conflict resolution and the bad-rule policy, without a database: rows are handed to the
 * engine in the order the store returns them ({@code priority}, then {@code id}).
 */
class RiskEngineTest {

    private static final UUID MERCHANT_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID MERCHANT_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final String OVER_1000 = "{\"field\":\"amount\",\"op\":\"gt\",\"value\":1000}";
    private static final String OVER_10 = "{\"field\":\"amount\",\"op\":\"gt\",\"value\":10}";

    private final RiskEngine engine = new RiskEngine(null, new RuleCompiler(JsonMapper.builder().build()));

    private static RiskFacts facts(UUID merchant, long amount) {
        return new RiskFacts(amount, "MOCK", merchant, "ORD-1", 12);
    }

    private RiskDecision decide(List<RuleRow> rows, RiskFacts facts) {
        return RiskEngine.decide(engine.compile(rows), facts);
    }

    @Test
    void noRulesMeansAllow() {
        assertThat(decide(List.of(), facts(MERCHANT_A, 5000))).isEqualTo(RiskDecision.allow());
    }

    @Test
    void noMatchMeansAllow() {
        var rows = List.of(new RuleRow(1, null, 100, OVER_1000, "REJECT", "AMOUNT_OVER_LIMIT"));
        assertThat(decide(rows, facts(MERCHANT_A, 1000))).isEqualTo(RiskDecision.allow());
    }

    @Test
    void aMatchCarriesTheRuleIdAndReason() {
        var rows = List.of(new RuleRow(7, null, 100, OVER_1000, "REJECT", "AMOUNT_OVER_LIMIT"));
        assertThat(decide(rows, facts(MERCHANT_A, 1001)))
                .isEqualTo(new RiskDecision(Outcome.REJECT, 7L, "AMOUNT_OVER_LIMIT"));
    }

    @Test
    void firstMatchInOrderWins() {
        var rows = List.of(
                new RuleRow(2, null, 10, OVER_10, "REJECT", "FIRST"),
                new RuleRow(1, null, 100, OVER_1000, "REJECT", "SECOND"));
        assertThat(decide(rows, facts(MERCHANT_A, 5000)).reasonCode()).isEqualTo("FIRST");
    }

    @Test
    void aRuleOfAnotherMerchantIsNotEvaluated() {
        var rows = List.of(new RuleRow(1, MERCHANT_B, 10, OVER_10, "REJECT", "B_ONLY"));
        assertThat(decide(rows, facts(MERCHANT_A, 5000))).isEqualTo(RiskDecision.allow());
        assertThat(decide(rows, facts(MERCHANT_B, 5000)).reasonCode()).isEqualTo("B_ONLY");
    }

    @Test
    void aMerchantRuleWinsOnlyThroughALowerPriorityNumber() {
        var merchantFirst = List.of(
                new RuleRow(2, MERCHANT_A, 10, OVER_10, "REJECT", "MERCHANT_LIMIT"),
                new RuleRow(1, null, 100, OVER_10, "REJECT", "GLOBAL_LIMIT"));
        assertThat(decide(merchantFirst, facts(MERCHANT_A, 5000)).reasonCode()).isEqualTo("MERCHANT_LIMIT");
        assertThat(decide(merchantFirst, facts(MERCHANT_B, 5000)).reasonCode()).isEqualTo("GLOBAL_LIMIT");

        var globalFirst = List.of(
                new RuleRow(1, null, 10, OVER_10, "REJECT", "GLOBAL_LIMIT"),
                new RuleRow(2, MERCHANT_A, 100, OVER_10, "REJECT", "MERCHANT_LIMIT"));
        assertThat(decide(globalFirst, facts(MERCHANT_A, 5000)).reasonCode()).isEqualTo("GLOBAL_LIMIT");
    }

    @Test
    void anInvalidRuleIsSkippedAndTheRestStillDecide() {
        var rows = List.of(
                new RuleRow(1, null, 1, "{\"field\":\"nope\",\"op\":\"eq\",\"value\":1}", "REJECT", "BROKEN"),
                new RuleRow(2, null, 2, OVER_10, "FLAG", "UNKNOWN_ACTION"),
                new RuleRow(3, null, 3, OVER_1000, "REJECT", "AMOUNT_OVER_LIMIT"));
        assertThat(engine.compile(rows)).extracting(CompiledRule::id).containsExactly(3L);
        assertThat(decide(rows, facts(MERCHANT_A, 5000)).reasonCode()).isEqualTo("AMOUNT_OVER_LIMIT");
    }
}
