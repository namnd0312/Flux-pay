package com.fluxpay.gateway.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.Map;
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

import tools.jackson.databind.ObjectMapper;

/**
 * A payment that matches a REVIEW rule is held: it exists (201) in status UNDER_REVIEW, no
 * provider has been called and there is nothing to pay yet. While held it still occupies its
 * order (a second create is a duplicate), the merchant can cancel it, and it ends by itself
 * when the review deadline passes. No webhook can move it.
 *
 * <p>The seeded review rule holds amounts above 20,000,000 (and up to 50,000,000, above which
 * the reject rule wins).
 */
class ReviewHoldIT extends AbstractIntegrationTest {

    private static final String DEMO = "demo-merchant";
    private static final long HELD_AMOUNT = 30_000_000;

    @Autowired
    MockMvc mvc;

    @Autowired
    CountingFakeProvider fake;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    PaymentSweepService sweeps;

    @Autowired
    ObjectMapper objectMapper;

    @BeforeEach
    void resetCounters() {
        fake.reset();
    }

    @AfterEach
    void reset() {
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

    /** Creates a held payment and returns its id. */
    private String hold(String orderId) throws Exception {
        String response = create(orderId, HELD_AMOUNT)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("UNDER_REVIEW"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("paymentId").stringValue();
    }

    private ResultActions fetch(String paymentId) throws Exception {
        return mvc.perform(get("/api/v1/payments/" + paymentId).with(hmac(DEMO, DEMO_SECRET, clock.millis(), null)));
    }

    private Map<String, Object> row(String paymentId) {
        return jdbc.sql("""
                        select status, provider_code, provider_ref, pay_url,
                               extract(epoch from (expires_at - created_at)) as lifetime_seconds
                        from payments where id = cast(:id as uuid)
                        """)
                .param("id", paymentId).query().singleRow();
    }

    private static String order() {
        return "ORD-" + UUID.randomUUID();
    }

    @Test
    void aReviewMatchHoldsThePaymentWithoutCallingTheProvider() throws Exception {
        String orderId = order();
        create(orderId, HELD_AMOUNT)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$.payUrl").value(nullValue()));

        assertThat(fake.calls()).isZero();
        Map<String, Object> stored = jdbc.sql("""
                        select status, provider_code, provider_ref, pay_url,
                               extract(epoch from (expires_at - created_at)) as lifetime_seconds
                        from payments where order_id = :o
                        """)
                .param("o", orderId).query().singleRow();
        assertThat(stored.get("status")).isEqualTo("UNDER_REVIEW");
        assertThat(stored.get("provider_code")).isEqualTo("MOCK");
        assertThat(stored.get("provider_ref")).isNull();
        assertThat(stored.get("pay_url")).isNull();
        // Held payments wait for the review timeout (30m), not the payment expiry (15m).
        assertThat(((Number) stored.get("lifetime_seconds")).longValue()).isEqualTo(1800);
    }

    @Test
    void theLedgerShowsTheHoldWithTheRuleReason() throws Exception {
        String id = hold(order());

        fetch(id).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$.transitions", hasSize(1)))
                .andExpect(jsonPath("$.transitions[0].from").value("CREATED"))
                .andExpect(jsonPath("$.transitions[0].to").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$.transitions[0].reasonCode").value("HIGH_AMOUNT"))
                .andExpect(jsonPath("$.transitions[0].source").value("SYSTEM"));
    }

    @Test
    void theHoldIsRecordedAsAReviewDecisionForThatPayment() throws Exception {
        String orderId = order();
        String id = hold(orderId);

        Map<String, Object> decision = jdbc.sql("""
                        select decision, reason_code, payment_id::text as payment_id
                        from risk_decisions where order_id = :o
                        """)
                .param("o", orderId).query().singleRow();
        assertThat(decision.get("decision")).isEqualTo("REVIEW");
        assertThat(decision.get("reason_code")).isEqualTo("HIGH_AMOUNT");
        assertThat(decision.get("payment_id")).isEqualTo(id);
    }

    @Test
    void aHeldPaymentStillOccupiesItsOrder() throws Exception {
        String orderId = order();
        hold(orderId);

        // New key, same order, while the first is held: a duplicate, whatever its amount.
        create(orderId, HELD_AMOUNT)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_ALREADY_EXISTS"));
        create(orderId, 1_000_000)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_ALREADY_EXISTS"));

        Integer rows = jdbc.sql("select count(*) from payments where order_id = :o").param("o", orderId)
                .query(Integer.class).single();
        assertThat(rows).isEqualTo(1);
        assertThat(fake.calls()).isZero();
    }

    @Test
    void theMerchantCanCancelAHeldPaymentAndThenReuseTheOrder() throws Exception {
        String orderId = order();
        String id = hold(orderId);

        mvc.perform(post("/api/v1/payments/" + id + "/cancel").with(hmac(DEMO, DEMO_SECRET, clock.millis(), null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.transitions", hasSize(2)))
                .andExpect(jsonPath("$.transitions[1].from").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$.transitions[1].reasonCode").value("MERCHANT_CANCEL"));

        create(orderId, 1_000_000)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void aHeldPaymentExpiresWhenTheReviewDeadlinePasses() throws Exception {
        String id = hold(order());

        clock.advance(Duration.ofMinutes(29));
        sweeps.expireOverdue();
        assertThat(row(id).get("status")).isEqualTo("UNDER_REVIEW");

        clock.advance(Duration.ofMinutes(2)); // past the 30m review timeout
        sweeps.expireOverdue();

        fetch(id).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPIRED"))
                .andExpect(jsonPath("$.transitions", hasSize(2)))
                .andExpect(jsonPath("$.transitions[1].from").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$.transitions[1].reasonCode").value("REVIEW_TIMEOUT"))
                .andExpect(jsonPath("$.transitions[1].source").value("SYSTEM"));
    }

    @Test
    void theAbandonedCreateSweepLeavesAHeldPaymentAlone() throws Exception {
        String id = hold(order());

        clock.advance(Duration.ofMinutes(5)); // past created-timeout (2m), before the review deadline
        sweeps.failAbandonedCreated();

        assertThat(row(id).get("status")).isEqualTo("UNDER_REVIEW");
    }

    @Test
    void aWebhookCannotMoveAHeldPayment() throws Exception {
        String id = hold(order());

        // The provider was never called, so no payment carries this reference; the event is
        // linked to the payment through merchantRef and refused for good.
        String json = webhookJson("evt-" + UUID.randomUUID(), "mp_fake_" + id, id, "SUCCESS", HELD_AMOUNT);
        mvc.perform(signedWebhook(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(false))
                .andExpect(jsonPath("$.rejectReason").value("PAYMENT_NEVER_ACCEPTED"));

        assertThat(row(id).get("status")).isEqualTo("UNDER_REVIEW");
    }

    @Test
    void theSameIdempotencyKeyReplaysTheHeldResponse() throws Exception {
        String orderId = order();
        String key = "idem-" + UUID.randomUUID();
        String first = create(orderId, HELD_AMOUNT, key).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String second = create(orderId, HELD_AMOUNT, key).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
        Integer rows = jdbc.sql("select count(*) from payments where order_id = :o").param("o", orderId)
                .query(Integer.class).single();
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void aboveTheRejectLimitIsStillRejectedNotHeld() throws Exception {
        create(order(), 50_000_001)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RISK_REJECTED"));
    }
}
