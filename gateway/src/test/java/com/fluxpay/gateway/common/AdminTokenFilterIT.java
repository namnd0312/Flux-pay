package com.fluxpay.gateway.common;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import com.fluxpay.gateway.AbstractIntegrationTest;
import com.fluxpay.gateway.MutableClock;

/**
 * The operator endpoints open with the admin token and nothing else, and the admin token
 * opens nothing but them: the two credentials do not overlap in either direction.
 */
class AdminTokenFilterIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    MutableClock clock;

    @AfterEach
    void reset() {
        clock.reset();
    }

    @Test
    void theCorrectTokenOpensTheOperatorEndpoints() throws Exception {
        mvc.perform(get("/internal/reviews").with(admin())).andExpect(status().isOk());
    }

    @Test
    void aMissingTokenIs401() throws Exception {
        mvc.perform(get("/internal/reviews"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ADMIN_AUTH_FAILED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "wrong", "test-admin-toke", "test-admin-token ", "TEST-ADMIN-TOKEN"})
    void aWrongTokenIs401(String token) throws Exception {
        mvc.perform(get("/internal/reviews").header("X-Admin-Token", token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ADMIN_AUTH_FAILED"));
    }

    @Test
    void everyOperatorEndpointIsGuarded() throws Exception {
        String id = UUID.randomUUID().toString();
        mvc.perform(post("/internal/reviews/" + id + "/approve")).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/reviews/" + id + "/reject")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/anything-else")).andExpect(status().isUnauthorized());
    }

    @Test
    void aValidMerchantSignatureDoesNotOpenTheOperatorEndpoints() throws Exception {
        mvc.perform(get("/internal/reviews").with(hmac("demo-merchant", DEMO_SECRET, clock.millis(), null)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ADMIN_AUTH_FAILED"));
    }

    @Test
    void theAdminTokenDoesNotOpenTheMerchantApi() throws Exception {
        mvc.perform(get("/api/v1/payments/" + UUID.randomUUID()).with(admin()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_MISSING_HEADERS"));
    }

    @Test
    void theMerchantApiStillWorksWithItsOwnCredentials() throws Exception {
        mvc.perform(get("/api/v1/payments/" + UUID.randomUUID())
                        .with(hmac("demo-merchant", DEMO_SECRET, clock.millis(), null)))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void aBlankConfiguredTokenStopsStartup(String configured) {
        assertThatThrownBy(() -> new AdminTokenFilter(configured, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FLUXPAY_ADMIN_TOKEN");
    }
}
