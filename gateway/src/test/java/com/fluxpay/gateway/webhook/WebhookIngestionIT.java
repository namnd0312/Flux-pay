package com.fluxpay.gateway.webhook;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import com.fluxpay.gateway.AbstractIntegrationTest;
import com.fluxpay.gateway.CountingFakeProvider;
import com.fluxpay.gateway.common.HmacSigner;
import com.fluxpay.gateway.payment.PaymentOrchestrator;
import com.jayway.jsonpath.JsonPath;

/**
 * End-to-end webhook ingestion against a real Postgres: a valid event applies the
 * transition; a duplicate leaves one row and one transition; a bad signature is 401 with
 * no row; amount/ref/illegal-transition cases are stored REJECTED and never change the
 * payment or return 5xx; an unknown-payment event is REJECTED and still de-duplicated.
 */
class WebhookIngestionIT extends AbstractIntegrationTest {

    private static final String DEMO = "demo-merchant";
    private static final long AMOUNT = 1_000_000L;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    HmacSigner signer;

    @Autowired
    com.fluxpay.gateway.MutableClock clock;

    @Autowired
    PaymentOrchestrator orchestrator;

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    // --- helpers ---

    private String createPayment() throws Exception {
        String body = "{\"orderId\":\"ORD-%s\",\"amount\":%d,\"returnUrl\":\"https://m/r\"}"
                .formatted(UUID.randomUUID(), AMOUNT);
        var res = mvc.perform(post("/api/v1/payments")
                .contentType(MediaType.APPLICATION_JSON).content(body)
                .with(hmac(DEMO, DEMO_SECRET, clock.millis(), "idem-" + UUID.randomUUID())))
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(res.getResponse().getContentAsString(), "$.paymentId");
    }

    private String providerRefOf(String paymentId) {
        return jdbc.sql("select provider_ref from payments where id = :id").param("id", UUID.fromString(paymentId))
                .query(String.class).single();
    }

    private String paymentStatus(String paymentId) {
        return jdbc.sql("select status from payments where id = :id").param("id", UUID.fromString(paymentId))
                .query(String.class).single();
    }

    private String webhookJson(String eventId, String providerRef, String merchantRef, String st, long amount) {
        return ("{\"eventId\":\"%s\",\"providerRef\":\"%s\",\"merchantRef\":\"%s\","
                + "\"status\":\"%s\",\"amount\":%d,\"occurredAt\":\"2026-01-01T00:00:00Z\"}")
                .formatted(eventId, providerRef, merchantRef, st, amount);
    }

    private org.springframework.test.web.servlet.ResultActions send(String json, boolean validSig) throws Exception {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        String sig = validSig
                ? signer.hex(CountingFakeProvider.TEST_WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), body)
                : "deadbeef";
        return mvc.perform(post("/webhooks/MOCK").header("X-Mock-Signature", sig)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private int eventCount(String eventId) {
        return jdbc.sql("select count(*) from webhook_events where event_id = :e").param("e", eventId)
                .query(Integer.class).single();
    }

    private int duplicateCount(String eventId) {
        return jdbc.sql("select duplicate_count from webhook_events where event_id = :e").param("e", eventId)
                .query(Integer.class).single();
    }

    // --- tests ---

    @Test
    void validWebhookApplies() throws Exception {
        String id = createPayment();
        String json = webhookJson("evt_" + UUID.randomUUID(), providerRefOf(id), id, "SUCCESS", AMOUNT);
        send(json, true).andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(true))
                .andExpect(jsonPath("$.duplicate").value(false));
        Assertions.assertThat(paymentStatus(id)).isEqualTo("SUCCEEDED");
    }

    @Test
    void duplicateLeavesOneRowAndOneTransition() throws Exception {
        String id = createPayment();
        String eventId = "evt_" + UUID.randomUUID();
        String json = webhookJson(eventId, providerRefOf(id), id, "SUCCESS", AMOUNT);
        send(json, true).andExpect(status().isOk()).andExpect(jsonPath("$.applied").value(true));
        send(json, true).andExpect(status().isOk()).andExpect(jsonPath("$.duplicate").value(true));

        Assertions.assertThat(eventCount(eventId)).isEqualTo(1);
        Assertions.assertThat(duplicateCount(eventId)).isEqualTo(1);
        int transitions = jdbc.sql("select count(*) from payment_transitions "
                        + "where payment_id = :id and reason_code = 'PROVIDER_WEBHOOK'")
                .param("id", UUID.fromString(id)).query(Integer.class).single();
        Assertions.assertThat(transitions).isEqualTo(1);
        Assertions.assertThat(paymentStatus(id)).isEqualTo("SUCCEEDED");
    }

    @Test
    void badSignatureIs401WithNoRow() throws Exception {
        String id = createPayment();
        String eventId = "evt_" + UUID.randomUUID();
        String json = webhookJson(eventId, providerRefOf(id), id, "SUCCESS", AMOUNT);
        send(json, false).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("WEBHOOK_BAD_SIGNATURE"));
        Assertions.assertThat(eventCount(eventId)).isZero();
        Assertions.assertThat(paymentStatus(id)).isEqualTo("PENDING");
    }

    @Test
    void amountMismatchIsRejected() throws Exception {
        String id = createPayment();
        String json = webhookJson("evt_" + UUID.randomUUID(), providerRefOf(id), id, "SUCCESS", 999L);
        send(json, true).andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(false))
                .andExpect(jsonPath("$.rejectReason").value("AMOUNT_MISMATCH"));
        Assertions.assertThat(paymentStatus(id)).isEqualTo("PENDING");
    }

    @Test
    void refMismatchIsRejected() throws Exception {
        String id = createPayment();
        String json = webhookJson("evt_" + UUID.randomUUID(), providerRefOf(id),
                UUID.randomUUID().toString(), "SUCCESS", AMOUNT);
        send(json, true).andExpect(status().isOk())
                .andExpect(jsonPath("$.rejectReason").value("REF_MISMATCH"));
        Assertions.assertThat(paymentStatus(id)).isEqualTo("PENDING");
    }

    @Test
    void lateWebhookOnExpiredIsRejected() throws Exception {
        String id = createPayment();
        clock.advance(Duration.ofMinutes(16));
        orchestrator.expireOverdue();
        Assertions.assertThat(paymentStatus(id)).isEqualTo("EXPIRED");

        String json = webhookJson("evt_" + UUID.randomUUID(), providerRefOf(id), id, "SUCCESS", AMOUNT);
        send(json, true).andExpect(status().isOk())
                .andExpect(jsonPath("$.rejectReason").value("ILLEGAL_TRANSITION"));
        Assertions.assertThat(paymentStatus(id)).isEqualTo("EXPIRED");
    }

    @Test
    void unknownPaymentIsRejectedAndDeduplicated() throws Exception {
        String eventId = "evt_" + UUID.randomUUID();
        String json = webhookJson(eventId, "mp_unknown_" + UUID.randomUUID(),
                UUID.randomUUID().toString(), "SUCCESS", AMOUNT);
        send(json, true).andExpect(status().isOk())
                .andExpect(jsonPath("$.rejectReason").value("UNKNOWN_PAYMENT"));
        send(json, true).andExpect(status().isOk()).andExpect(jsonPath("$.duplicate").value(true));
        Assertions.assertThat(eventCount(eventId)).isEqualTo(1);
        Assertions.assertThat(duplicateCount(eventId)).isEqualTo(1);
    }
}
