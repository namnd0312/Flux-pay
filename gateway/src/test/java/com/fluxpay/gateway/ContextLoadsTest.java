package com.fluxpay.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;

/**
 * Proves the skeleton is wired: the full context boots against a real Postgres,
 * Flyway migrations apply, actuator health reports UP, and the Boot 4
 * {@code RestClient.Builder} bean (needed for provider calls from phase 3) is
 * resolvable.
 */
class ContextLoadsTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    // If the restclient starter were missing, this bean would not exist and the context would fail to load.
    @Autowired
    RestClient.Builder restClientBuilder;

    @Test
    void healthIsUp() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void restClientBuilderIsResolvable() {
        assertThat(restClientBuilder).isNotNull();
    }
}
