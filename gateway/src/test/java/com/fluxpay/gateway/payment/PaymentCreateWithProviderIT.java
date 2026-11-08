package com.fluxpay.gateway.payment;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

/**
 * Create through the provider layer: happy path goes PENDING with exactly one provider
 * call made outside any transaction (the fake asserts that); a provider failure
 * compensates the committed CREATED row to FAILED and answers 502 with no exception text;
 * an unknown provider is rejected before any row is written.
 */
class PaymentCreateWithProviderIT extends AbstractIntegrationTest {

    private static final String DEMO = "demo-merchant";

    @Autowired
    MockMvc mvc;

    @Autowired
    CountingFakeProvider fake;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MutableClock clock;

    @AfterEach
    void resetFake() {
        fake.reset();
        clock.reset();
    }

    private String body(String orderId, String provider) {
        String providerField = provider == null ? "" : ",\"provider\":\"%s\"".formatted(provider);
        return "{\"orderId\":\"%s\",\"amount\":1000000,\"returnUrl\":\"https://m/r\"%s}".formatted(orderId, providerField);
    }

    @Test
    void happyPathGoesPendingWithOneProviderCall() throws Exception {
        fake.reset();
        String content = body("ORD-" + UUID.randomUUID(), null);
        mvc.perform(post("/api/v1/payments").contentType(MediaType.APPLICATION_JSON).content(content)
                        .with(hmac(DEMO, DEMO_SECRET, clock.millis(), "idem-" + UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.payUrl", containsString("/mock/v1/pay/")));
        org.assertj.core.api.Assertions.assertThat(fake.calls()).isEqualTo(1);
    }

    @Test
    void providerDownCompensatesToFailedWith502() throws Exception {
        fake.failNextCalls(true);
        String orderId = "ORD-" + UUID.randomUUID();
        mvc.perform(post("/api/v1/payments").contentType(MediaType.APPLICATION_JSON).content(body(orderId, null))
                        .with(hmac(DEMO, DEMO_SECRET, clock.millis(), "idem-" + UUID.randomUUID())))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("PROVIDER_UNAVAILABLE"))
                .andExpect(jsonPath("$.detail", not(containsString("Simulated"))));

        String stored = jdbc.sql("select status from payments where order_id = :o").param("o", orderId)
                .query(String.class).single();
        org.assertj.core.api.Assertions.assertThat(stored).isEqualTo("FAILED");
    }

    @Test
    void unknownProviderIs400WithNoRow() throws Exception {
        String orderId = "ORD-" + UUID.randomUUID();
        mvc.perform(post("/api/v1/payments").contentType(MediaType.APPLICATION_JSON).content(body(orderId, "NOPE"))
                        .with(hmac(DEMO, DEMO_SECRET, clock.millis(), "idem-" + UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_PROVIDER"));

        Integer rows = jdbc.sql("select count(*) from payments where order_id = :o").param("o", orderId)
                .query(Integer.class).single();
        org.assertj.core.api.Assertions.assertThat(rows).isZero();
    }
}
