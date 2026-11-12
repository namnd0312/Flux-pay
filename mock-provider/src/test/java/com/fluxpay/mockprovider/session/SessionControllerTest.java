package com.fluxpay.mockprovider.session;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fluxpay.mockprovider.config.MockProperties;
import com.fluxpay.mockprovider.webhook.WebhookSender;

import com.jayway.jsonpath.JsonPath;

/** The fake provider's session endpoint creates a session with a pay URL and serves it back. */
@WebMvcTest(SessionController.class)
@Import(PaymentSessionStore.class)
class SessionControllerTest {

    @Autowired
    MockMvc mvc;

    /** The controller can trigger a webhook (auto-pay); it is off by default and never called here. */
    @MockitoBean
    WebhookSender sender;

    /** The web slice does not load AsyncConfig, so bind the mock's properties here. */
    @TestConfiguration
    @EnableConfigurationProperties(MockProperties.class)
    static class Props {
    }

    @Test
    void createThenGet() throws Exception {
        var created = mvc.perform(post("/mock/v1/payments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantRef\":\"abc\",\"amount\":1000,\"description\":\"d\",\"returnUrl\":\"https://m/r\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.providerRef").exists())
                .andExpect(jsonPath("$.payUrl").exists())
                .andReturn();
        String providerRef = JsonPath.read(created.getResponse().getContentAsString(), "$.providerRef");

        mvc.perform(get("/mock/v1/payments/" + providerRef))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merchantRef").value("abc"))
                .andExpect(jsonPath("$.status").value("NEW"));
        verifyNoInteractions(sender);
    }

    @Test
    void getUnknownIs404() throws Exception {
        mvc.perform(get("/mock/v1/payments/does-not-exist")).andExpect(status().isNotFound());
    }
}
