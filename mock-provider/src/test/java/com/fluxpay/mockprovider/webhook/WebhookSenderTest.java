package com.fluxpay.mockprovider.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.fluxpay.mockprovider.config.MockProperties;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.json.JsonMapper;

/**
 * Delivery rules of the fake provider: retry until acknowledged, but only for answers that
 * may change (5xx / 429); every attempt carries the same signed bytes; duplicates are extra
 * copies sent after a successful delivery. Runs synchronously here because the plain
 * object is not wrapped in the @Async proxy; backoff is 1 ms to keep the test fast.
 */
class WebhookSenderTest {

    private static final String URL = "http://gw/webhooks/MOCK";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final List<String> bodies = new ArrayList<>();

    @Test
    void sendsDuplicatesWithSameBodyAndSignature() {
        WebhookSender sender = sender(2, 6); // 2 extra copies -> 3 sends in total
        server.expect(ExpectedCount.times(3), requestTo(URL))
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

    @Test
    void retriesAfter503WithTheSameBytesUntilAcknowledged() {
        WebhookSender sender = sender(0, 6);
        server.expect(ExpectedCount.times(2), requestTo(URL))
                .andExpect(request -> bodies.add(((MockClientHttpRequest) request).getBodyAsString()))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(ExpectedCount.once(), requestTo(URL))
                .andExpect(request -> bodies.add(((MockClientHttpRequest) request).getBodyAsString()))
                .andRespond(withSuccess());

        sender.send("mp_1", "merchant-1", 1000L, "SUCCESS");

        server.verify();
        assertThat(bodies).hasSize(3);
        assertThat(bodies).containsOnly(bodies.get(0)); // same eventId, same bytes every time
        assertThat(count("mock.webhook.retried")).isEqualTo(2.0);
    }

    @Test
    void doesNotRetryAnAnswerThatCanNeverChange() {
        WebhookSender sender = sender(3, 6);
        server.expect(ExpectedCount.once(), requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        sender.send("mp_1", "merchant-1", 1000L, "SUCCESS");

        server.verify(); // exactly one request: no retry, and no duplicates of an undelivered event
        assertThat(meters.find("mock.webhook.retried").counter()).isNull();
    }

    @Test
    void stopsAfterTheLastAttemptAndCountsItAsExhausted() {
        WebhookSender sender = sender(0, 3);
        server.expect(ExpectedCount.times(3), requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        sender.send("mp_1", "merchant-1", 1000L, "SUCCESS");

        server.verify();
        assertThat(count("mock.webhook.exhausted")).isEqualTo(1.0);
        assertThat(count("mock.webhook.sent")).isEqualTo(3.0);
    }

    private WebhookSender sender(int duplicates, int maxAttempts) {
        var props = new MockProperties("sekret", "http://gw", "http://localhost:8081",
                new MockProperties.Callback(0, duplicates, "NONE", new MockProperties.Retry(maxAttempts, 1)),
                new MockProperties.Create(0), new MockProperties.SenderPool(2, 10));
        return new WebhookSender(builder, JsonMapper.builder().build(), props, meters);
    }

    private double count(String name) {
        return meters.get(name).counter().count();
    }
}
