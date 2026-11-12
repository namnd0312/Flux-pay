package com.fluxpay.gateway.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import com.fluxpay.gateway.AbstractIntegrationTest;
import com.fluxpay.gateway.CountingFakeProvider;
import com.fluxpay.gateway.CountingFakeProvider.CreateGate;
import com.fluxpay.gateway.MutableClock;

/**
 * A webhook that arrives while the create flow is still between its two transactions: the
 * payment row exists (CREATED) but carries no provider reference yet. The fake provider
 * parks the create call so the test can deliver the webhook inside exactly that window.
 *
 * <p>Before this was handled the gateway answered 200 {@code UNKNOWN_PAYMENT}; the dedup
 * row then swallowed every redelivery and a paid order stayed PENDING for good. Now the
 * answer is 503 with the event kept RETRYABLE, so the provider's redelivery is processed.
 * The remaining cases pin what a redelivery gets in every other ledger state.
 */
class EarlyWebhookIT extends AbstractIntegrationTest {

    private static final long AMOUNT = 1_000_000L;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    CountingFakeProvider fake;

    private final ExecutorService pool = Executors.newFixedThreadPool(3);

    @AfterEach
    void reset() {
        fake.reset();
        clock.reset();
        pool.shutdownNow();
    }

    @Test
    void earlyWebhookIsAskedToComeBackAndItsRedeliveryIsApplied() throws Exception {
        CreateGate gate = fake.blockNextCreate();
        Future<MockHttpServletResponse> create = submitCreate("ORD-" + UUID.randomUUID());
        UUID id = gate.awaitEntered();
        String eventId = "evt_" + UUID.randomUUID();
        String json = webhookJson(eventId, gate.providerRef(), id.toString(), "SUCCESS", AMOUNT);

        mvc.perform(signedWebhook(json))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.code").value("WEBHOOK_RETRY_LATER"))
                .andExpect(jsonPath("$.reason").value("PAYMENT_NOT_READY"));
        assertThat(eventField(eventId, "processing_status")).isEqualTo("RETRYABLE");
        assertThat(paymentStatus(id)).isEqualTo("CREATED");

        gate.release();
        assertThat(create.get(15, TimeUnit.SECONDS).getStatus()).isEqualTo(201);

        // The provider sends the very same bytes again.
        mvc.perform(signedWebhook(json)).andExpect(status().isOk()).andExpect(jsonPath("$.applied").value(true));
        assertThat(paymentStatus(id)).isEqualTo("SUCCEEDED");
        assertThat(eventField(eventId, "processing_status")).isEqualTo("APPLIED");
        assertThat(eventField(eventId, "reject_reason")).isNull();
        assertThat(eventField(eventId, "payment_id")).isEqualTo(id.toString());
        assertThat(terminalTransitions(id)).containsExactly("SUCCEEDED");
    }

    @Test
    void webhookForAPaymentWhoseCreateFailedIsRejectedButStaysLinked() throws Exception {
        String orderId = "ORD-" + UUID.randomUUID();
        fake.failNextCalls(true);
        assertThat(submitCreate(orderId).get(15, TimeUnit.SECONDS).getStatus()).isEqualTo(502);
        UUID id = jdbc.sql("select id from payments where order_id = :o").param("o", orderId)
                .query(UUID.class).single();
        assertThat(paymentStatus(id)).isEqualTo("FAILED");

        String eventId = "evt_" + UUID.randomUUID();
        mvc.perform(signedWebhook(webhookJson(eventId, "mp_fake_" + id, id.toString(), "SUCCESS", AMOUNT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(false))
                .andExpect(jsonPath("$.rejectReason").value("PAYMENT_NEVER_ACCEPTED"));

        // The event that says "money was taken" must be findable from the payment later.
        assertThat(eventField(eventId, "payment_id")).isEqualTo(id.toString());
        assertThat(paymentStatus(id)).isEqualTo("FAILED");
    }

    @Test
    void redeliveryWhileTheFirstDeliveryIsStillRunningIsToldToRetry() throws Exception {
        // A RECEIVED row is a delivery that has claimed the event and not finished yet.
        String eventId = "evt_" + UUID.randomUUID();
        String json = webhookJson(eventId, "mp_x", UUID.randomUUID().toString(), "SUCCESS", AMOUNT);
        jdbc.sql("insert into webhook_events (id, provider_code, event_id, raw_body, processing_status, "
                        + "duplicate_count, received_at) values (:id, 'MOCK', :e, :raw, 'RECEIVED', 0, now())")
                .param("id", UUID.randomUUID()).param("e", eventId).param("raw", json).update();

        mvc.perform(signedWebhook(json))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.reason").value("EVENT_IN_PROGRESS"));
        assertThat(eventField(eventId, "processing_status")).isEqualTo("RECEIVED");
    }

    @Test
    void redeliveryWithDifferentBytesIsAcknowledgedButNeverProcessed() throws Exception {
        CreateGate gate = fake.blockNextCreate();
        Future<MockHttpServletResponse> create = submitCreate("ORD-" + UUID.randomUUID());
        UUID id = gate.awaitEntered();
        String eventId = "evt_" + UUID.randomUUID();
        String original = webhookJson(eventId, gate.providerRef(), id.toString(), "FAILED", AMOUNT);
        String tampered = webhookJson(eventId, gate.providerRef(), id.toString(), "SUCCESS", AMOUNT);
        mvc.perform(signedWebhook(original)).andExpect(status().isServiceUnavailable());
        gate.release();
        assertThat(create.get(15, TimeUnit.SECONDS).getStatus()).isEqualTo(201);

        mvc.perform(signedWebhook(tampered))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.applied").value(false))
                .andExpect(jsonPath("$.rejectReason").value("EVENT_BODY_MISMATCH"));

        assertThat(eventField(eventId, "processing_status")).isEqualTo("RETRYABLE");
        assertThat(eventField(eventId, "raw_body")).isEqualTo(original);
        assertThat(paymentStatus(id)).isEqualTo("PENDING");
    }

    @Test
    void concurrentRedeliveriesOfARetryableEventApplyItExactlyOnce() throws Exception {
        CreateGate gate = fake.blockNextCreate();
        Future<MockHttpServletResponse> create = submitCreate("ORD-" + UUID.randomUUID());
        UUID id = gate.awaitEntered();
        String eventId = "evt_" + UUID.randomUUID();
        String json = webhookJson(eventId, gate.providerRef(), id.toString(), "SUCCESS", AMOUNT);
        mvc.perform(signedWebhook(json)).andExpect(status().isServiceUnavailable());
        gate.release();
        assertThat(create.get(15, TimeUnit.SECONDS).getStatus()).isEqualTo(201);

        CyclicBarrier start = new CyclicBarrier(2);
        Future<MockHttpServletResponse> first = pool.submit(() -> {
            start.await(5, TimeUnit.SECONDS);
            return mvc.perform(signedWebhook(json)).andReturn().getResponse();
        });
        Future<MockHttpServletResponse> second = pool.submit(() -> {
            start.await(5, TimeUnit.SECONDS);
            return mvc.perform(signedWebhook(json)).andReturn().getResponse();
        });
        List<MockHttpServletResponse> responses = List.of(first.get(15, TimeUnit.SECONDS),
                second.get(15, TimeUnit.SECONDS));

        // One reclaims the event and applies it. The other is either told the first is still
        // running (503) or, if it arrived after the first finished, sees a plain duplicate (200).
        long applied = 0;
        for (MockHttpServletResponse r : responses) {
            assertThat(r.getStatus()).isIn(200, 503);
            if (r.getStatus() == 200 && r.getContentAsString().contains("\"applied\":true")) {
                applied++;
            }
        }
        assertThat(applied).isEqualTo(1);
        assertThat(paymentStatus(id)).isEqualTo("SUCCEEDED");
        assertThat(terminalTransitions(id)).containsExactly("SUCCEEDED");
        assertThat(eventField(eventId, "processing_status")).isEqualTo("APPLIED");
    }

    // --- helpers ---

    private Future<MockHttpServletResponse> submitCreate(String orderId) {
        String body = "{\"orderId\":\"%s\",\"amount\":%d,\"returnUrl\":\"https://m/r\"}".formatted(orderId, AMOUNT);
        return pool.submit(() -> mvc.perform(post("/api/v1/payments")
                .contentType(MediaType.APPLICATION_JSON).content(body)
                .with(hmac("demo-merchant", DEMO_SECRET, clock.millis(), "idem-" + UUID.randomUUID())))
                .andReturn().getResponse());
    }

    private String paymentStatus(UUID id) {
        return jdbc.sql("select status from payments where id = :id").param("id", id).query(String.class).single();
    }

    private List<String> terminalTransitions(UUID id) {
        return jdbc.sql("select to_status from payment_transitions where payment_id = :id "
                        + "and to_status in ('SUCCEEDED','FAILED','EXPIRED','CANCELLED')").param("id", id)
                .query(String.class).list();
    }

    private String eventField(String eventId, String column) {
        return jdbc.sql("select " + column + "::text from webhook_events where event_id = :e").param("e", eventId)
                .query((rs, n) -> java.util.Optional.ofNullable(rs.getString(1))).single().orElse(null);
    }
}
