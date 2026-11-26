package com.fluxpay.gateway.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.fluxpay.gateway.AbstractIntegrationTest;
import com.fluxpay.gateway.MutableClock;
import com.fluxpay.gateway.StatementCounter;

/**
 * The rule cache against the real table. A rule changed by SQL does nothing until the cache
 * reloads (the staleness window) and decides requests afterwards, with no restart; and the
 * create request itself sends no query to {@code risk_rules}.
 *
 * <p>Importing {@link StatementCounter.Config} gives this class its own application context,
 * and so its own cache: reloading here does not touch the cache other IT classes use. The
 * rule rows are still shared, so they are restored after each test.
 */
@Import(StatementCounter.Config.class)
class RiskRuleCacheIT extends AbstractIntegrationTest {

    private static final String SEED = "global-amount-limit";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    RiskRuleCache cache;

    @Autowired
    StatementCounter counter;

    @AfterEach
    void restoreSeedRules() {
        counter.stop();
        jdbc.sql("delete from risk_rules where name like 'it-%'").update();
        jdbc.sql("""
                update risk_rules set enabled = true, condition = '{"field":"amount","op":"gt","value":50000000}'
                where name = :seed
                """).param("seed", SEED).update();
        cache.refresh();
    }

    private ResultActions create(long amount) throws Exception {
        String body = "{\"orderId\":\"ORD-%s\",\"amount\":%d}".formatted(UUID.randomUUID(), amount);
        return mvc.perform(post("/api/v1/payments").contentType(MediaType.APPLICATION_JSON).content(body)
                .with(hmac("demo-merchant", DEMO_SECRET, clock.millis(), "idem-" + UUID.randomUUID())));
    }

    private void setSeedLimit(long limit) {
        jdbc.sql("""
                update risk_rules set condition = cast(:condition as jsonb) where name = :seed
                """)
                .param("condition", "{\"field\":\"amount\",\"op\":\"gt\",\"value\":%d}".formatted(limit))
                .param("seed", SEED)
                .update();
    }

    @Test
    void aThresholdChangedBySqlAppliesAfterRefreshAndNotBefore() throws Exception {
        setSeedLimit(5_000_000);

        // Staleness window: the row has changed, the gateway still decides with what it loaded.
        create(6_000_000).andExpect(status().isCreated());

        assertThat(cache.refresh()).isTrue();

        create(6_000_000)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail", containsString("AMOUNT_OVER_LIMIT")));
        create(5_000_000).andExpect(status().isCreated());
    }

    @Test
    void aDisabledRuleStopsApplyingAfterRefresh() throws Exception {
        create(50_000_001).andExpect(status().isUnprocessableEntity());

        jdbc.sql("update risk_rules set enabled = false where name = :seed").param("seed", SEED).update();
        cache.refresh();

        create(50_000_001).andExpect(status().isCreated());
    }

    @Test
    void aRuleEditedIntoABrokenOneIsDroppedAtRefresh() throws Exception {
        jdbc.sql("update risk_rules set condition = '{\"all\":[]}' where name = :seed").param("seed", SEED).update();

        assertThat(cache.refresh()).isTrue();

        // Fail-open for that rule: what it used to stop now goes through.
        create(50_000_001).andExpect(status().isCreated());
    }

    @Test
    void anAllowedCreateReadsNoRuleAndWritesOneDecision() throws Exception {
        counter.start();
        create(1_000_000).andExpect(status().isCreated());
        List<String> sql = counter.stop();

        assertThat(sql).noneMatch(s -> s.contains("risk_rules"));
        assertThat(sql).filteredOn(s -> s.contains("risk_decisions")).hasSize(1);
        assertThat(sql).as("statements for one allowed create").hasSize(8);
    }

    @Test
    void aRejectedCreateReadsNoRuleAndWritesOneDecision() throws Exception {
        counter.start();
        create(50_000_001).andExpect(status().isUnprocessableEntity());
        List<String> sql = counter.stop();

        assertThat(sql).noneMatch(s -> s.contains("risk_rules"));
        assertThat(sql).filteredOn(s -> s.contains("risk_decisions")).hasSize(1);
        assertThat(sql).as("statements for one rejected create").hasSize(4);
    }
}
