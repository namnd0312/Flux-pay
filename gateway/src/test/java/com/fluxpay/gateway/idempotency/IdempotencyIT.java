package com.fluxpay.gateway.idempotency;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.fluxpay.gateway.AbstractIntegrationTest;
import com.fluxpay.gateway.CountingFakeProvider;
import com.fluxpay.gateway.MutableClock;
import com.fluxpay.gateway.common.CanonicalRequest;
import com.jayway.jsonpath.JsonPath;

/**
 * End-to-end idempotency on POST /api/v1/payments through the signed filter chain: a repeat
 * replays the stored response without a second provider call; a reused key with a different
 * body is 422; an in-progress key is 409; a missing key is 400; a duplicate live order (new
 * key) is 409 while the first stays live but re-creatable once it is no longer live; a 502
 * is stored and replayed without hitting the provider again.
 */
class IdempotencyIT extends AbstractIntegrationTest {

    private static final String DEMO = "demo-merchant";
    private static final UUID DEMO_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired
    MockMvc mvc;

    @Autowired
    MutableClock clock;

    @Autowired
    CountingFakeProvider fake;

    @Autowired
    IdempotencyService idempotency;

    @AfterEach
    void reset() {
        clock.reset();
        fake.reset();
    }

    private String body(String orderId, long amount) {
        return "{\"orderId\":\"" + orderId + "\",\"amount\":" + amount + "}";
    }

    private ResultActions create(String orderId, long amount, String key) throws Exception {
        String content = body(orderId, amount);
        return mvc.perform(post("/api/v1/payments").contentType(MediaType.APPLICATION_JSON).content(content)
                .with(hmac(DEMO, DEMO_SECRET, clock.millis(), key)));
    }

    @Test
    void repeatReplaysStoredResponseWithoutSecondProviderCall() throws Exception {
        String order = "ORD-" + UUID.randomUUID();
        String key = "key-" + UUID.randomUUID();
        var first = create(order, 1000000, key).andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotency-Replayed")).andReturn();
        String firstId = JsonPath.read(first.getResponse().getContentAsString(), "$.paymentId");

        var replayed = create(order, 1000000, key).andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "true")).andReturn();
        String replayId = JsonPath.read(replayed.getResponse().getContentAsString(), "$.paymentId");

        Assertions.assertThat(replayId).isEqualTo(firstId);
        Assertions.assertThat(fake.calls()).isEqualTo(1); // replay never reached the controller
    }

    @Test
    void sameKeyDifferentBodyIs422() throws Exception {
        String order = "ORD-" + UUID.randomUUID();
        String key = "key-" + UUID.randomUUID();
        create(order, 1000000, key).andExpect(status().isCreated());
        create(order, 2000000, key).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void inProgressKeyIs409() throws Exception {
        // Leave a key IN_PROGRESS (claimed but not completed), then retry it via the API.
        String order = "ORD-" + UUID.randomUUID();
        String key = "key-" + UUID.randomUUID();
        String content = body(order, 1000000);
        String hash = CanonicalRequest.sha256Hex(content.getBytes(StandardCharsets.UTF_8));
        idempotency.begin(DEMO_ID, key, hash); // NEW, leaves IN_PROGRESS with a fresh lease

        create(order, 1000000, key).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_IN_PROGRESS"));
        Assertions.assertThat(fake.calls()).isZero();
    }

    @Test
    void missingKeyIs400() throws Exception {
        create("ORD-" + UUID.randomUUID(), 1000000, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_MISSING"));
    }

    @Test
    void duplicateLiveOrderWithNewKeyIs409() throws Exception {
        String order = "ORD-" + UUID.randomUUID();
        create(order, 1000000, "key-" + UUID.randomUUID()).andExpect(status().isCreated());
        create(order, 1000000, "key-" + UUID.randomUUID()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_ALREADY_EXISTS"));
    }

    @Test
    void failedOrderIsRecreatableWithNewKey() throws Exception {
        String order = "ORD-" + UUID.randomUUID();
        fake.failNextCalls(true);
        create(order, 1000000, "key-" + UUID.randomUUID()).andExpect(status().isBadGateway()); // -> FAILED
        fake.failNextCalls(false);
        create(order, 1000000, "key-" + UUID.randomUUID()).andExpect(status().isCreated()); // FAILED does not block
    }

    @Test
    void providerFailureIsStoredAndReplayedWithoutSecondCall() throws Exception {
        String order = "ORD-" + UUID.randomUUID();
        String key = "key-" + UUID.randomUUID();
        fake.failNextCalls(true);
        create(order, 1000000, key).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("PROVIDER_UNAVAILABLE"));
        Assertions.assertThat(fake.calls()).isEqualTo(1);

        // Replay: same key -> stored 502 returned without touching the provider again.
        create(order, 1000000, key).andExpect(status().isBadGateway())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.code").value("PROVIDER_UNAVAILABLE"));
        Assertions.assertThat(fake.calls()).isEqualTo(1); // no second provider call
    }
}
