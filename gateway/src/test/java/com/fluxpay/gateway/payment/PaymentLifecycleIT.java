package com.fluxpay.gateway.payment;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import com.fluxpay.gateway.AbstractIntegrationTest;
import com.fluxpay.gateway.MutableClock;
import com.fluxpay.gateway.merchant.SecretCipher;
import com.jayway.jsonpath.JsonPath;

/**
 * Drives the payment lifecycle end to end against a real Postgres through MockMvc:
 * create, cancel, illegal re-cancel, cross-merchant isolation, and clock-driven expiry.
 * Every request is HMAC-signed (phase 5) via {@link #hmac}; the cross-merchant case signs
 * as a second merchant whose encrypted secret the test seeds with {@link SecretCipher}.
 */
class PaymentLifecycleIT extends AbstractIntegrationTest {

    private static final String DEMO = "demo-merchant";
    private static final String OTHER = "other-merchant";
    private static final String OTHER_SECRET = "other-secret";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    SecretCipher cipher;

    @Autowired
    PaymentOrchestrator orchestrator;

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    private String create(String merchant, String secret, String orderId) throws Exception {
        String body = """
                {"orderId":"%s","amount":1000000,"description":"d","returnUrl":"https://m.example/r"}
                """.formatted(orderId);
        var result = mvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(hmac(merchant, secret, clock.millis(), "idem-" + UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.paymentId");
    }

    @Test
    void createYieldsPendingWithOneTransition() throws Exception {
        String id = create(DEMO, DEMO_SECRET, "ORD-" + UUID.randomUUID());
        mvc.perform(get("/api/v1/payments/" + id).with(hmac(DEMO, DEMO_SECRET, clock.millis(), null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.transitions", hasSize(1)))
                .andExpect(jsonPath("$.transitions[0].from").value("CREATED"))
                .andExpect(jsonPath("$.transitions[0].to").value("PENDING"));
    }

    @Test
    void cancelThenCancelAgainIs409() throws Exception {
        String id = create(DEMO, DEMO_SECRET, "ORD-" + UUID.randomUUID());
        mvc.perform(post("/api/v1/payments/" + id + "/cancel").with(hmac(DEMO, DEMO_SECRET, clock.millis(), null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(post("/api/v1/payments/" + id + "/cancel").with(hmac(DEMO, DEMO_SECRET, clock.millis(), null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_ILLEGAL_TRANSITION"));
    }

    @Test
    void anotherMerchantCannotSeeOrCancel() throws Exception {
        seedMerchant(OTHER, OTHER_SECRET);
        String id = create(DEMO, DEMO_SECRET, "ORD-" + UUID.randomUUID());
        mvc.perform(get("/api/v1/payments/" + id).with(hmac(OTHER, OTHER_SECRET, clock.millis(), null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
        mvc.perform(post("/api/v1/payments/" + id + "/cancel").with(hmac(OTHER, OTHER_SECRET, clock.millis(), null)))
                .andExpect(status().isNotFound());
    }

    @Test
    void missingAuthHeadersIs401() throws Exception {
        mvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"ORD-X\",\"amount\":1000}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_MISSING_HEADERS"));
    }

    @Test
    void sweeperExpiresOverduePending() throws Exception {
        String id = create(DEMO, DEMO_SECRET, "ORD-" + UUID.randomUUID());
        clock.advance(Duration.ofMinutes(16)); // expiry is 15m
        int expired = orchestrator.expireOverdue();
        org.assertj.core.api.Assertions.assertThat(expired).isGreaterThanOrEqualTo(1);
        mvc.perform(get("/api/v1/payments/" + id).with(hmac(DEMO, DEMO_SECRET, clock.millis(), null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPIRED"));
    }

    private void seedMerchant(String apiKey, String secret) {
        Integer n = jdbc.sql("select count(*) from merchants where api_key = :k").param("k", apiKey)
                .query(Integer.class).single();
        if (n == 0) {
            jdbc.sql("insert into merchants (id, name, api_key, secret, active) "
                            + "values (:id, :name, :k, :s, true)")
                    .param("id", UUID.randomUUID())
                    .param("name", apiKey)
                    .param("k", apiKey)
                    .param("s", cipher.encrypt(apiKey, secret))
                    .update();
        }
    }
}
