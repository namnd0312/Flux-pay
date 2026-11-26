package com.fluxpay.gateway.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.fluxpay.gateway.AbstractIntegrationTest;
import com.fluxpay.gateway.CountingFakeProvider;
import com.fluxpay.gateway.MutableClock;
import com.fluxpay.gateway.risk.RiskRuleCache;

/**
 * Risk rules at payment creation. A matching REJECT rule answers 422 RISK_REJECTED before any
 * payment row is written or any provider is called; rules are data, so a rule inserted or
 * changed by SQL decides requests once the rule cache has reloaded; a rule row that breaks the
 * grammar is skipped and never causes a 500.
 *
 * <p>The seeded rule is "amount above 50,000,000 is rejected". Rules added by a test are named
 * {@code it-...} and removed afterwards, and the cache is reloaded, because every IT class
 * shares this database and this application context.
 */
class RiskRejectAtCreateIT extends AbstractIntegrationTest {

    private static final String DEMO = "demo-merchant";
    private static final String DEMO_ID = "00000000-0000-0000-0000-000000000001";

    @Autowired
    MockMvc mvc;

    @Autowired
    CountingFakeProvider fake;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    RiskRuleCache ruleCache;

    @BeforeEach
    void resetCounters() {
        fake.reset();
    }

    @AfterEach
    void restoreSeedRules() {
        jdbc.sql("delete from risk_rules where name like 'it-%'").update();
        jdbc.sql("update risk_rules set enabled = true where name = 'global-amount-limit'").update();
        ruleCache.refresh();
        fake.reset();
        clock.reset();
    }

    private ResultActions create(String orderId, long amount, String idempotencyKey) throws Exception {
        String body = "{\"orderId\":\"%s\",\"amount\":%d}".formatted(orderId, amount);
        return mvc.perform(post("/api/v1/payments").contentType(MediaType.APPLICATION_JSON).content(body)
                .with(hmac(DEMO, DEMO_SECRET, clock.millis(), idempotencyKey)));
    }

    private ResultActions create(String orderId, long amount) throws Exception {
        return create(orderId, amount, "idem-" + UUID.randomUUID());
    }

    private void insertRule(String name, String merchantId, int priority, String condition, String reasonCode) {
        jdbc.sql("""
                        insert into risk_rules (name, merchant_id, priority, condition, action, reason_code)
                        values (:name, cast(:merchant as uuid), :priority, cast(:condition as jsonb), 'REJECT', :reason)
                        """)
                .param("name", name)
                .param("merchant", merchantId)
                .param("priority", priority)
                .param("condition", condition)
                .param("reason", reasonCode)
                .update();
        ruleCache.refresh();
    }

    private int paymentRows(String orderId) {
        return jdbc.sql("select count(*) from payments where order_id = :o").param("o", orderId)
                .query(Integer.class).single();
    }

    private static String order() {
        return "ORD-" + UUID.randomUUID();
    }

    @Test
    void overTheLimitIsRejectedWithNoRowAndNoProviderCall() throws Exception {
        String orderId = order();
        create(orderId, 50_000_001)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RISK_REJECTED"))
                .andExpect(jsonPath("$.detail", containsString("AMOUNT_OVER_LIMIT")));

        assertThat(paymentRows(orderId)).isZero();
        assertThat(fake.calls()).isZero();
    }

    @Test
    void exactlyAtTheLimitIsAllowed() throws Exception {
        create(order(), 50_000_000)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"));
        assertThat(fake.calls()).isEqualTo(1);
    }

    @Test
    void aRuleInsertedBySqlDecidesRequestsAfterTheCacheReloads() throws Exception {
        create(order(), 3_000_000).andExpect(status().isCreated());

        insertRule("it-lower-limit", null, 50, "{\"field\":\"amount\",\"op\":\"gt\",\"value\":2000000}",
                "AMOUNT_OVER_LOWER_LIMIT");

        create(order(), 3_000_000)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail", containsString("AMOUNT_OVER_LOWER_LIMIT")));
        create(order(), 2_000_000).andExpect(status().isCreated());
    }

    @Test
    void aMerchantRuleWithALowerPriorityNumberBeatsTheGlobalRule() throws Exception {
        insertRule("it-demo-merchant-limit", DEMO_ID, 10,
                "{\"field\":\"amount\",\"op\":\"gt\",\"value\":2000000}", "MERCHANT_LIMIT");

        // Both rules match 60,000,000; the merchant rule is tried first.
        create(order(), 60_000_000)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail", containsString("MERCHANT_LIMIT")));
    }

    @Test
    void aRuleOfAnotherMerchantDoesNotApply() throws Exception {
        insertRule("it-other-merchant-limit", "00000000-0000-0000-0000-0000000000ff", 10,
                "{\"field\":\"amount\",\"op\":\"gt\",\"value\":1}", "OTHER_MERCHANT_LIMIT");

        create(order(), 1_000_000).andExpect(status().isCreated());
    }

    @Test
    void aRuleThatBreaksTheGrammarIsSkippedAndNeverCausesA500() throws Exception {
        insertRule("it-unknown-field", null, 1, "{\"field\":\"customerEmail\",\"op\":\"eq\",\"value\":\"a\"}", "BROKEN");
        insertRule("it-empty-group", null, 2, "{\"all\":[]}", "BROKEN");
        insertRule("it-not-an-object", null, 3, "\"amount > 5\"", "BROKEN");
        insertRule("it-text-number", null, 4, "{\"field\":\"amount\",\"op\":\"gt\",\"value\":\"1\"}", "BROKEN");

        create(order(), 1_000_000).andExpect(status().isCreated());
        create(order(), 50_000_001)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail", containsString("AMOUNT_OVER_LIMIT")));
    }

    @Test
    void theSameIdempotencyKeyReplaysTheRejectionEvenAfterTheRuleIsGone() throws Exception {
        String orderId = order();
        String key = "idem-" + UUID.randomUUID();
        create(orderId, 50_000_001, key).andExpect(status().isUnprocessableEntity());

        jdbc.sql("update risk_rules set enabled = false where name = 'global-amount-limit'").update();
        ruleCache.refresh();

        // Same key: the stored 422 is replayed, the rules are not asked again.
        create(orderId, 50_000_001, key)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RISK_REJECTED"));
        assertThat(paymentRows(orderId)).isZero();

        // A new key is a new request and sees the rules as they are now.
        create(orderId, 50_000_001).andExpect(status().isCreated());
    }
}
