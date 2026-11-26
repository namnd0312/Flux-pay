package com.fluxpay.gateway.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fluxpay.gateway.risk.RiskRuleStore.RuleRow;

import tools.jackson.databind.json.JsonMapper;

/**
 * What the cache does when the rule table changes, breaks, or holds a bad row. The store is
 * replaced by a stub whose answer the test controls, so a database failure can be produced
 * on demand.
 */
class RiskRuleCacheTest {

    private static final RuleRow LIMIT_1000 =
            new RuleRow(1, null, 100, "{\"field\":\"amount\",\"op\":\"gt\",\"value\":1000}", "REJECT", "LIMIT_1000");
    private static final RuleRow LIMIT_10 =
            new RuleRow(2, null, 50, "{\"field\":\"amount\",\"op\":\"gt\",\"value\":10}", "REJECT", "LIMIT_10");
    private static final RuleRow BROKEN =
            new RuleRow(3, null, 1, "{\"field\":\"amout\",\"op\":\"gt\",\"value\":1}", "REJECT", "TYPO");

    /** A store that returns whatever the test last set, or fails. */
    private static final class StubStore extends RiskRuleStore {
        List<RuleRow> rows = List.of();
        boolean down;

        StubStore() {
            super(null);
        }

        @Override
        public List<RuleRow> findEnabled() {
            if (down) {
                throw new IllegalStateException("database is down");
            }
            return rows;
        }
    }

    private final StubStore store = new StubStore();
    private final RiskRuleCache cache = new RiskRuleCache(store, new RuleCompiler(JsonMapper.builder().build()));

    private List<String> activeReasons() {
        return cache.current().stream().map(CompiledRule::reasonCode).toList();
    }

    @Test
    void readingBeforeTheFirstLoadFails() {
        assertThatThrownBy(cache::current).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theFirstLoadFillsTheCache() {
        store.rows = List.of(LIMIT_1000);
        cache.afterSingletonsInstantiated();
        assertThat(activeReasons()).containsExactly("LIMIT_1000");
    }

    @Test
    void aFailedFirstLoadStopsStartup() {
        store.down = true;
        assertThatThrownBy(cache::afterSingletonsInstantiated).hasMessageContaining("database is down");
    }

    @Test
    void aChangeIsInvisibleUntilRefresh() {
        store.rows = List.of(LIMIT_1000);
        cache.afterSingletonsInstantiated();

        store.rows = List.of(LIMIT_10, LIMIT_1000);
        assertThat(activeReasons()).containsExactly("LIMIT_1000");

        assertThat(cache.refresh()).isTrue();
        assertThat(activeReasons()).containsExactly("LIMIT_10", "LIMIT_1000");
    }

    @Test
    void refreshReplacesTheListInsteadOfChangingIt() {
        store.rows = List.of(LIMIT_1000);
        cache.afterSingletonsInstantiated();
        List<CompiledRule> seenByARequestInFlight = cache.current();

        store.rows = List.of(LIMIT_10);
        cache.refresh();

        assertThat(seenByARequestInFlight).extracting(CompiledRule::reasonCode).containsExactly("LIMIT_1000");
        assertThatThrownBy(() -> seenByARequestInFlight.add(null)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aRefreshThatCannotReadTheRulesKeepsTheLastGoodList() {
        store.rows = List.of(LIMIT_1000);
        cache.afterSingletonsInstantiated();

        store.down = true;
        assertThat(cache.refresh()).isFalse();
        assertThat(activeReasons()).containsExactly("LIMIT_1000");

        store.down = false;
        store.rows = List.of(LIMIT_10);
        assertThat(cache.refresh()).isTrue();
        assertThat(activeReasons()).containsExactly("LIMIT_10");
    }

    @Test
    void aRowThatDoesNotCompileIsLeftOutAndTheOthersLoad() {
        store.rows = List.of(BROKEN, LIMIT_1000);
        cache.afterSingletonsInstantiated();
        assertThat(activeReasons()).containsExactly("LIMIT_1000");
    }

    @Test
    void aRuleEditedIntoABrokenOneStopsApplying() {
        store.rows = List.of(LIMIT_1000);
        cache.afterSingletonsInstantiated();

        // Same id, condition now invalid: the rule is dropped, it does not keep its old meaning.
        store.rows = List.of(new RuleRow(1, null, 100, "{\"all\":[]}", "REJECT", "LIMIT_1000"));
        assertThat(cache.refresh()).isTrue();
        assertThat(cache.current()).isEmpty();
    }
}
