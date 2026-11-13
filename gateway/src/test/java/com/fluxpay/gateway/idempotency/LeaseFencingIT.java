package com.fluxpay.gateway.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import com.fluxpay.gateway.AbstractIntegrationTest;
import com.fluxpay.gateway.CountingFakeProvider;
import com.fluxpay.gateway.CountingFakeProvider.CreateGate;
import com.fluxpay.gateway.MutableClock;
import com.fluxpay.gateway.idempotency.IdempotencyOutcome.Kind;

/**
 * The "zombie" worker: request A claims an idempotency key, stalls for longer than the
 * lease (it is slow, not dead), request B takes the key over, and then A wakes up and tries
 * to write. Without a fence, A's late write overwrites or deletes what B stored.
 *
 * <p>The fence is the {@code lease_epoch} token: each acquisition gets a new one and every
 * owner write presents it, so A's write matches zero rows. "Stalls past the lease" is
 * produced by moving the test clock, which makes the service-level cases deterministic
 * without threads.
 */
@ExtendWith(OutputCaptureExtension.class)
class LeaseFencingIT extends AbstractIntegrationTest {

    private static final Duration PAST_THE_LEASE = Duration.ofSeconds(61); // lease is 60s

    @Autowired
    IdempotencyService service;

    @Autowired
    MutableClock clock;

    @Autowired
    CountingFakeProvider fake;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    @BeforeEach
    void startClean() {
        fake.reset(); // the fake is shared by every IT; other classes leave its call counter non-zero
    }

    @AfterEach
    void reset() {
        fake.reset();
        clock.reset();
        pool.shutdownNow();
    }

    @Test
    void zombieIsRefusedWhileTheNewOwnerIsStillRunning() {
        // The pure fencing case: the key is still IN_PROGRESS when the zombie writes, so the
        // token is the only thing that can tell the two owners apart.
        UUID m = UUID.randomUUID();
        long epochA = service.begin(m, "k", "h").leaseEpoch();
        clock.advance(PAST_THE_LEASE);
        long epochB = service.begin(m, "k", "h").leaseEpoch();

        assertThat(service.complete(m, "k", epochA, 502, "{\"from\":\"A\"}")).isFalse();
        assertThat(service.abandon(m, "k", epochA)).isFalse();

        assertThat(service.complete(m, "k", epochB, 201, "{\"from\":\"B\"}")).isTrue();
        IdempotencyOutcome replay = service.begin(m, "k", "h");
        assertThat(replay.responseStatus()).isEqualTo(201);
        assertThat(replay.responseBody()).contains("B");
    }

    @Test
    void zombieCannotOverwriteTheResponseStoredByTheNewOwner() {
        UUID m = UUID.randomUUID();
        long epochA = service.begin(m, "k", "h").leaseEpoch();
        clock.advance(PAST_THE_LEASE);
        long epochB = service.begin(m, "k", "h").leaseEpoch(); // take-over
        assertThat(epochB).isGreaterThan(epochA);

        assertThat(service.complete(m, "k", epochB, 201, "{\"from\":\"B\"}")).isTrue();
        assertThat(service.complete(m, "k", epochA, 502, "{\"from\":\"A\"}")).isFalse();

        IdempotencyOutcome replay = service.begin(m, "k", "h");
        assertThat(replay.kind()).isEqualTo(Kind.REPLAY);
        assertThat(replay.responseStatus()).isEqualTo(201);
        assertThat(replay.responseBody()).contains("B");
    }

    @Test
    void zombieCannotDeleteTheKeyOfTheNewOwner() {
        UUID m = UUID.randomUUID();
        long epochA = service.begin(m, "k", "h").leaseEpoch();
        clock.advance(PAST_THE_LEASE);
        long epochB = service.begin(m, "k", "h").leaseEpoch();
        assertThat(service.complete(m, "k", epochB, 201, "{\"from\":\"B\"}")).isTrue();

        assertThat(service.abandon(m, "k", epochA)).isFalse();

        assertThat(service.begin(m, "k", "h").kind()).isEqualTo(Kind.REPLAY); // still there
    }

    @Test
    void zombieCannotTouchAFreshClaimMadeAfterTheKeyWasReleased() {
        // The case a counter kept inside the row would miss: the row is deleted and created
        // again, so an in-row "epoch" would start over and equal the zombie's old value.
        UUID m = UUID.randomUUID();
        long epochA = service.begin(m, "k", "h").leaseEpoch();
        clock.advance(PAST_THE_LEASE);
        long epochB = service.begin(m, "k", "h").leaseEpoch();
        assertThat(service.abandon(m, "k", epochB)).isTrue(); // B got a non-storable answer: key released
        IdempotencyOutcome c = service.begin(m, "k", "h");    // merchant retries: brand-new row
        assertThat(c.kind()).isEqualTo(Kind.NEW);
        assertThat(c.leaseEpoch()).isNotEqualTo(epochA).isGreaterThan(epochB);

        assertThat(service.complete(m, "k", epochA, 502, "{\"from\":\"A\"}")).isFalse();
        assertThat(service.abandon(m, "k", epochA)).isFalse();

        // C still owns the key and can finish normally.
        assertThat(service.begin(m, "k", "h").kind()).isEqualTo(Kind.IN_PROGRESS);
        assertThat(service.complete(m, "k", c.leaseEpoch(), 201, "{\"from\":\"C\"}")).isTrue();
    }

    @Test
    void stalledCreateRequestKeepsItsOwnResponseButStoresNothing(CapturedOutput output) throws Exception {
        String key = "idem-" + UUID.randomUUID();
        String orderId = "ORD-" + UUID.randomUUID();
        String body = "{\"orderId\":\"%s\",\"amount\":1000000,\"returnUrl\":\"https://m/r\"}".formatted(orderId);

        // A: claims the key, commits the CREATED payment, then stalls inside the provider call.
        CreateGate gate = fake.blockNextCreate();
        Future<MockHttpServletResponse> a = pool.submit(() -> create(body, key));
        gate.awaitEntered();
        clock.advance(PAST_THE_LEASE);

        // B: same key and body. Takes the lease over and runs. A's payment row already holds
        // the order, so B's own attempt ends 409 ORDER_ALREADY_EXISTS, and B stores that.
        MockHttpServletResponse b = create(body, key);
        assertThat(b.getStatus()).isEqualTo(409);
        assertThat(b.getContentAsString()).contains("ORDER_ALREADY_EXISTS");

        // A wakes up and finishes. Its client gets A's real answer...
        gate.release();
        MockHttpServletResponse aResponse = a.get(15, TimeUnit.SECONDS);
        assertThat(aResponse.getStatus()).isEqualTo(201);
        assertThat(aResponse.getHeader("Idempotency-Replayed")).isNull();
        assertThat(output.getOut()).contains("Idempotency lease lost").contains(key);

        // ...but the stored outcome is still the current owner's (B's), not overwritten by A.
        MockHttpServletResponse replay = create(body, key);
        assertThat(replay.getStatus()).isEqualTo(409);
        assertThat(replay.getHeader("Idempotency-Replayed")).isEqualTo("true");
        assertThat(replay.getContentAsString()).contains("ORDER_ALREADY_EXISTS");

        // And through all of it there is exactly one payment and one provider call.
        assertThat(jdbc.sql("select count(*) from payments where order_id = :o").param("o", orderId)
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(fake.calls()).isEqualTo(1);
    }

    private MockHttpServletResponse create(String body, String key) throws Exception {
        return mvc.perform(post("/api/v1/payments").contentType(MediaType.APPLICATION_JSON).content(body)
                .with(hmac("demo-merchant", DEMO_SECRET, clock.millis(), key))).andReturn().getResponse();
    }
}
