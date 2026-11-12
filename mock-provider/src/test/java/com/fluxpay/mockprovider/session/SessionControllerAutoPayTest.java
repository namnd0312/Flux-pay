package com.fluxpay.mockprovider.session;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fluxpay.mockprovider.config.MockProperties;
import com.fluxpay.mockprovider.webhook.WebhookSender;
import com.jayway.jsonpath.JsonPath;

/**
 * With {@code mock.callback.auto-pay} set, creating a session pays it immediately and
 * triggers the webhook from inside the create call: the setup for the early-webhook race.
 */
@WebMvcTest(SessionController.class)
@Import(PaymentSessionStore.class)
@TestPropertySource(properties = "mock.callback.auto-pay=SUCCESS")
class SessionControllerAutoPayTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    WebhookSender sender;

    @TestConfiguration
    @EnableConfigurationProperties(MockProperties.class)
    static class Props {
    }

    @Test
    void createPaysTheSessionAndSendsTheWebhook() throws Exception {
        var created = mvc.perform(post("/mock/v1/payments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantRef\":\"abc\",\"amount\":1000,\"description\":\"d\",\"returnUrl\":\"https://m/r\"}"))
                .andExpect(status().isCreated()).andReturn();
        String providerRef = JsonPath.read(created.getResponse().getContentAsString(), "$.providerRef");

        verify(sender).send(eq(providerRef), eq("abc"), eq(1000L), eq("SUCCESS"));
        mvc.perform(get("/mock/v1/payments/" + providerRef)).andExpect(jsonPath("$.status").value("SUCCESS"));
    }
}
