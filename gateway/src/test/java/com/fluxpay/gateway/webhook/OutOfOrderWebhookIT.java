package com.fluxpay.gateway.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import com.fluxpay.gateway.AbstractIntegrationTest;
import com.fluxpay.gateway.CountingFakeProvider;
import com.fluxpay.gateway.MutableClock;
import com.jayway.jsonpath.JsonPath;

/**
 * Providers give no ordering guarantee: two different events about one payment can arrive
 * in either order. The rule is "first terminal state wins". Whatever arrives second is
 * recorded as REJECTED (so the disagreement is visible for reconciliation) and never
 * overwrites the payment.
 */
class OutOfOrderWebhookIT extends AbstractIntegrationTest {

    private static final long AMOUNT = 1_000_000L;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    CountingFakeProvider fake;

    @AfterEach
    void reset() {
        fake.reset();
        clock.reset();
    }

    @Test
    void failedThenSuccessStaysFailed() throws Exception {
        assertFirstTerminalWins("FAILED", "SUCCESS", "FAILED");
    }

    @Test
    void successThenFailedStaysSucceeded() throws Exception {
        assertFirstTerminalWins("SUCCESS", "FAILED", "SUCCEEDED");
    }

    private void assertFirstTerminalWins(String firstEvent, String secondEvent, String expectedStatus)
            throws Exception {
        String id = createPendingPayment();
        String ref = "mp_fake_" + id;
        String secondId = "evt_" + UUID.randomUUID();

        mvc.perform(signedWebhook(webhookJson("evt_" + UUID.randomUUID(), ref, id, firstEvent, AMOUNT)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.applied").value(true));
        mvc.perform(signedWebhook(webhookJson(secondId, ref, id, secondEvent, AMOUNT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(false))
                .andExpect(jsonPath("$.rejectReason").value("ILLEGAL_TRANSITION"));

        UUID pid = UUID.fromString(id);
        assertThat(jdbc.sql("select status from payments where id = :id").param("id", pid)
                .query(String.class).single()).isEqualTo(expectedStatus);
        List<String> terminals = jdbc.sql("select to_status from payment_transitions where payment_id = :id "
                        + "and to_status in ('SUCCEEDED','FAILED','EXPIRED','CANCELLED')").param("id", pid)
                .query(String.class).list();
        assertThat(terminals).containsExactly(expectedStatus);
        assertThat(jdbc.sql("select processing_status from webhook_events where event_id = :e")
                .param("e", secondId).query(String.class).single()).isEqualTo("REJECTED");
    }

    private String createPendingPayment() throws Exception {
        String body = "{\"orderId\":\"ORD-%s\",\"amount\":%d,\"returnUrl\":\"https://m/r\"}"
                .formatted(UUID.randomUUID(), AMOUNT);
        var res = mvc.perform(post("/api/v1/payments")
                .contentType(MediaType.APPLICATION_JSON).content(body)
                .with(hmac("demo-merchant", DEMO_SECRET, clock.millis(), "idem-" + UUID.randomUUID())))
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(res.getResponse().getContentAsString(), "$.paymentId");
    }
}
