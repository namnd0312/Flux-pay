package com.fluxpay.gateway.idempotency;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.assertj.core.api.Assertions;
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
 * The whole-increment acceptance test: 50 concurrent identical create requests (same
 * Idempotency-Key and body) must collapse to exactly one payment row and one provider call.
 * The database primary key on idempotency_keys is the only arbiter; the losers get a replay
 * (201) or an in-progress conflict (409), never a second payment.
 */
class ConcurrentCreateIT extends AbstractIntegrationTest {

    private static final String DEMO = "demo-merchant";
    private static final int THREADS = 50;

    @Autowired
    MockMvc mvc;

    @Autowired
    MutableClock clock;

    @Autowired
    CountingFakeProvider fake;

    @Autowired
    JdbcClient jdbc;

    @AfterEach
    void reset() {
        clock.reset();
        fake.reset();
    }

    @Test
    void fiftyIdenticalCreatesYieldOnePaymentAndOneProviderCall() throws Exception {
        String order = "ORD-" + UUID.randomUUID();
        String key = "key-" + UUID.randomUUID();
        String content = "{\"orderId\":\"" + order + "\",\"amount\":1000000}";
        long ts = clock.millis();

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        Set<Integer> statuses = ConcurrentHashMap.newKeySet();
        AtomicInteger errors = new AtomicInteger();

        for (int i = 0; i < THREADS; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    int status = mvc.perform(post("/api/v1/payments")
                                    .contentType(MediaType.APPLICATION_JSON).content(content)
                                    .with(hmac(DEMO, DEMO_SECRET, ts, key)))
                            .andReturn().getResponse().getStatus();
                    statuses.add(status);
                } catch (Exception ex) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        Assertions.assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        Assertions.assertThat(errors.get()).isZero();
        Integer rows = jdbc.sql("select count(*) from payments where order_id = :o").param("o", order)
                .query(Integer.class).single();
        Assertions.assertThat(rows).isEqualTo(1);
        Assertions.assertThat(fake.calls()).isEqualTo(1);
        // Losers are either a replay (201) or an in-progress conflict (409); never a 5xx or a 422.
        Assertions.assertThat(statuses).isSubsetOf(201, 409);
    }
}
