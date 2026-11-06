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
import com.jayway.jsonpath.JsonPath;

/**
 * Drives the payment lifecycle end to end against a real Postgres through MockMvc:
 * create, cancel, illegal re-cancel, cross-merchant isolation, and clock-driven expiry.
 */
class PaymentLifecycleIT extends AbstractIntegrationTest {

    private static final String DEMO = "demo-merchant";
    private static final String OTHER = "other-merchant";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    PaymentOrchestrator orchestrator;

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    private String create(String merchant, String orderId) throws Exception {
        String body = """
                {"orderId":"%s","amount":1000000,"description":"d","returnUrl":"https://m.example/r"}
                """.formatted(orderId);
        var result = mvc.perform(post("/api/v1/payments")
                        .header("X-Merchant-Id", merchant)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.paymentId");
    }

    @Test
    void createYieldsPendingWithOneTransition() throws Exception {
        String id = create(DEMO, "ORD-" + UUID.randomUUID());
        mvc.perform(get("/api/v1/payments/" + id).header("X-Merchant-Id", DEMO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.transitions", hasSize(1)))
                .andExpect(jsonPath("$.transitions[0].from").value("CREATED"))
                .andExpect(jsonPath("$.transitions[0].to").value("PENDING"));
    }

    @Test
    void cancelThenCancelAgainIs409() throws Exception {
        String id = create(DEMO, "ORD-" + UUID.randomUUID());
        mvc.perform(post("/api/v1/payments/" + id + "/cancel").header("X-Merchant-Id", DEMO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(post("/api/v1/payments/" + id + "/cancel").header("X-Merchant-Id", DEMO))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_ILLEGAL_TRANSITION"));
    }

    @Test
    void anotherMerchantCannotSeeOrCancel() throws Exception {
        seedMerchant(OTHER);
        String id = create(DEMO, "ORD-" + UUID.randomUUID());
        mvc.perform(get("/api/v1/payments/" + id).header("X-Merchant-Id", OTHER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
        mvc.perform(post("/api/v1/payments/" + id + "/cancel").header("X-Merchant-Id", OTHER))
                .andExpect(status().isNotFound());
    }

    @Test
    void missingMerchantHeaderIs401() throws Exception {
        mvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"ORD-X\",\"amount\":1000}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_UNKNOWN_MERCHANT"));
    }

    @Test
    void sweeperExpiresOverduePending() throws Exception {
        String id = create(DEMO, "ORD-" + UUID.randomUUID());
        clock.advance(Duration.ofMinutes(16)); // expiry is 15m
        int expired = orchestrator.expireOverdue();
        org.assertj.core.api.Assertions.assertThat(expired).isGreaterThanOrEqualTo(1);
        mvc.perform(get("/api/v1/payments/" + id).header("X-Merchant-Id", DEMO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPIRED"));
    }

    private void seedMerchant(String apiKey) {
        Integer n = jdbc.sql("select count(*) from merchants where api_key = :k").param("k", apiKey)
                .query(Integer.class).single();
        if (n == 0) {
            jdbc.sql("insert into merchants (id, name, api_key, secret, active) "
                            + "values (:id, :name, :k, :s, true)")
                    .param("id", UUID.randomUUID())
                    .param("name", apiKey)
                    .param("k", apiKey)
                    .param("s", "secret")
                    .update();
        }
    }
}
