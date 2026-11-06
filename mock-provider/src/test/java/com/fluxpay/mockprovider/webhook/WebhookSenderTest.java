package com.fluxpay.mockprovider.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.fluxpay.mockprovider.config.MockProperties;

import tools.jackson.databind.json.JsonMapper;

/**
 * The sender delivers the configured number of copies (1 + duplicates), all carrying the
 * same signed body (same eventId) and an X-Mock-Signature header. Runs synchronously here
 * because the plain object is not wrapped in the @Async proxy.
 */
class WebhookSenderTest {

    @Test
    void sendsDuplicatesWithSameBodyAndSignature() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var props = new MockProperties("sekret", "http://gw", "http://localhost:8081",
                new MockProperties.Callback(0, 2)); // 2 extra -> 3 total
        var sender = new WebhookSender(builder, JsonMapper.builder().build(), props);

        List<String> bodies = new ArrayList<>();
        server.expect(ExpectedCount.times(3), requestTo("http://gw/webhooks/MOCK"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    var mock = (MockClientHttpRequest) request;
                    assertThat(mock.getHeaders().getFirst("X-Mock-Signature")).isNotBlank();
                    bodies.add(mock.getBodyAsString());
                })
                .andRespond(withSuccess("{\"received\":true}", MediaType.APPLICATION_JSON));

        sender.send("mp_1", "merchant-1", 1000L, "SUCCESS");

        server.verify();
        assertThat(bodies).hasSize(3);
        assertThat(bodies.get(1)).isEqualTo(bodies.get(0));
        assertThat(bodies.get(2)).isEqualTo(bodies.get(0));
    }
}
