package com.fluxpay.gateway.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.fluxpay.gateway.AbstractIntegrationTest;
import com.fluxpay.gateway.CountingFakeProvider;
import com.fluxpay.gateway.MutableClock;

import tools.jackson.databind.ObjectMapper;

/**
 * Every evaluated create leaves exactly one {@code risk_decisions} row that explains it: an
 * allowed request is tied to its payment, a rejected one names the rule, and both keep the
 * facts the rules saw. A request that was never evaluated (idempotent replay) or whose
 * payment insert rolled back (duplicate order) leaves no row.
 */
class RiskDecisionAuditIT extends AbstractIntegrationTest {

    private static final String DEMO_ID = "00000000-0000-0000-0000-000000000001";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    CountingFakeProvider fake;

    @Autowired
    ObjectMapper objectMapper;

    @AfterEach
    void reset() {
        fake.reset();
        clock.reset();
    }

    private ResultActions create(String orderId, long amount, String idempotencyKey) throws Exception {
        String body = "{\"orderId\":\"%s\",\"amount\":%d}".formatted(orderId, amount);
        return mvc.perform(post("/api/v1/payments").contentType(MediaType.APPLICATION_JSON).content(body)
                .with(hmac("demo-merchant", DEMO_SECRET, clock.millis(), idempotencyKey)));
    }

    private ResultActions create(String orderId, long amount) throws Exception {
        return create(orderId, amount, "idem-" + UUID.randomUUID());
    }

    private List<Map<String, Object>> decisions(String orderId) {
        return jdbc.sql("""
                        select merchant_id::text as merchant_id, payment_id::text as payment_id, decision,
                               matched_rule_id, reason_code, facts::text as facts
                        from risk_decisions where order_id = :o order by id
                        """)
                .param("o", orderId).query().listOfRows();
    }

    private static String order() {
        return "ORD-" + UUID.randomUUID();
    }

    @Test
    void anAllowedCreateIsRecordedWithItsPayment() throws Exception {
        String orderId = order();
        String response = create(orderId, 1_000_000).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String paymentId = objectMapper.readTree(response).get("paymentId").stringValue();

        assertThat(decisions(orderId)).singleElement().satisfies(row -> {
            assertThat(row.get("decision")).isEqualTo("ALLOW");
            assertThat(row.get("payment_id")).isEqualTo(paymentId);
            assertThat(row.get("merchant_id")).isEqualTo(DEMO_ID);
            assertThat(row.get("matched_rule_id")).isNull();
            assertThat(row.get("reason_code")).isNull();
        });
    }

    @Test
    void aRejectedCreateIsRecordedWithTheRuleAndNoPayment() throws Exception {
        Long seedRuleId = jdbc.sql("select id from risk_rules where name = 'global-amount-limit'")
                .query(Long.class).single();
        String orderId = order();
        create(orderId, 50_000_001).andExpect(status().isUnprocessableEntity());

        assertThat(decisions(orderId)).singleElement().satisfies(row -> {
            assertThat(row.get("decision")).isEqualTo("REJECT");
            assertThat(row.get("payment_id")).isNull();
            assertThat(row.get("matched_rule_id")).isEqualTo(seedRuleId);
            assertThat(row.get("reason_code")).isEqualTo("AMOUNT_OVER_LIMIT");
        });
    }

    @Test
    void theFactsSnapshotHoldsTheFiveFactsAndNothingElse() throws Exception {
        String orderId = order();
        create(orderId, 1_000_000).andExpect(status().isCreated());

        String facts = (String) decisions(orderId).get(0).get("facts");
        Map<?, ?> snapshot = objectMapper.readValue(facts, Map.class);

        // The test clock is 2026-01-01T00:00Z, which is 07:00 in Asia/Ho_Chi_Minh.
        assertThat(snapshot).isEqualTo(Map.of(
                "amount", 1_000_000,
                "provider", "MOCK",
                "merchantId", DEMO_ID,
                "orderId", orderId,
                "hourOfDay", 7));
    }

    @Test
    void aDuplicateOrderLeavesNoSecondDecision() throws Exception {
        String orderId = order();
        create(orderId, 1_000_000).andExpect(status().isCreated());

        // New key, same live order: the payment insert fails and takes its decision row with it.
        create(orderId, 1_000_000).andExpect(status().isConflict());

        assertThat(decisions(orderId)).hasSize(1);
    }

    @Test
    void anIdempotentReplayIsNotEvaluatedAgain() throws Exception {
        String orderId = order();
        String key = "idem-" + UUID.randomUUID();
        create(orderId, 50_000_001, key).andExpect(status().isUnprocessableEntity());
        create(orderId, 50_000_001, key).andExpect(status().isUnprocessableEntity());

        assertThat(decisions(orderId)).hasSize(1);
    }

    @Test
    void aProviderFailureKeepsTheDecisionOfTheFailedPayment() throws Exception {
        fake.failNextCalls(true);
        String orderId = order();
        create(orderId, 1_000_000).andExpect(status().isBadGateway());

        String status = jdbc.sql("select status from payments where order_id = :o").param("o", orderId)
                .query(String.class).single();
        assertThat(status).isEqualTo("FAILED");
        assertThat(decisions(orderId)).singleElement()
                .satisfies(row -> assertThat(row.get("decision")).isEqualTo("ALLOW"));
    }
}
