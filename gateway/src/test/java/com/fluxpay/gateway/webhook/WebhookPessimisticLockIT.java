package com.fluxpay.gateway.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fluxpay.gateway.AbstractIntegrationTest;
import com.fluxpay.gateway.CountingFakeProvider;
import com.fluxpay.gateway.MutableClock;
import com.jayway.jsonpath.JsonPath;

/**
 * The webhook path in pessimistic mode ({@code SELECT ... FOR UPDATE}). The test plays the
 * "other writer": it opens its own transaction, locks the payment row, and only then lets a
 * webhook in.
 *
 * <p>"The webhook is blocked" cannot be observed with a latch, because the waiting happens
 * inside PostgreSQL. So the test asks the database itself: {@code pg_stat_activity} shows a
 * backend with {@code wait_event_type = 'Lock'} while a session waits for a row lock.
 *
 * <p>The lock timeout is shortened for this class so the timeout case stays fast.
 */
@TestPropertySource(properties = "fluxpay.webhook.lock-timeout=1500ms")
class WebhookPessimisticLockIT extends AbstractIntegrationTest {

    private static final long AMOUNT = 1_000_000L;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    CountingFakeProvider fake;

    @Autowired
    PlatformTransactionManager txManager;

    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    @AfterEach
    void reset() {
        fake.reset();
        clock.reset();
        pool.shutdownNow();
    }

    @Test
    void webhookWaitsForTheRowLockAndAppliesOnceItIsReleased() throws Exception {
        String id = createPendingPayment();
        String json = webhookJson("evt_" + UUID.randomUUID(), "mp_fake_" + id, id, "SUCCESS", AMOUNT);
        AtomicReference<Future<MockHttpServletResponse>> webhook = new AtomicReference<>();

        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            lockRow(id);
            webhook.set(pool.submit(() -> mvc.perform(signedWebhook(json)).andReturn().getResponse()));
            awaitABackendWaitingForALock();
            // Leaving this block commits and releases the lock; the webhook then proceeds.
        });

        MockHttpServletResponse res = webhook.get().get(15, TimeUnit.SECONDS);
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(res.getContentAsString(), "$.applied")).isTrue();
        assertThat(paymentStatus(id)).isEqualTo("SUCCEEDED");
    }

    @Test
    void webhookGivesUpAfterTheLockTimeoutAndItsRedeliveryIsApplied() throws Exception {
        String id = createPendingPayment();
        String eventId = "evt_" + UUID.randomUUID();
        String json = webhookJson(eventId, "mp_fake_" + id, id, "SUCCESS", AMOUNT);
        AtomicReference<MockHttpServletResponse> blocked = new AtomicReference<>();

        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            lockRow(id);
            Future<MockHttpServletResponse> webhook =
                    pool.submit(() -> mvc.perform(signedWebhook(json)).andReturn().getResponse());
            try {
                // Keep holding the lock until the webhook has answered: it must time out.
                blocked.set(webhook.get(15, TimeUnit.SECONDS));
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        });

        assertThat(blocked.get().getStatus()).isEqualTo(503);
        assertThat(JsonPath.<String>read(blocked.get().getContentAsString(), "$.reason")).isEqualTo("LOCK_TIMEOUT");
        assertThat(jdbc.sql("select processing_status from webhook_events where event_id = :e")
                .param("e", eventId).query(String.class).single()).isEqualTo("RETRYABLE");
        assertThat(paymentStatus(id)).isEqualTo("PENDING");

        mvc.perform(signedWebhook(json)).andExpect(status().isOk()).andExpect(jsonPath("$.applied").value(true));
        assertThat(paymentStatus(id)).isEqualTo("SUCCEEDED");

        // The timeout was set for one transaction only. Ask on the same executor thread that
        // ran the timed-out webhook: the pool hands a thread back the connection it used last,
        // so this reads the setting on the very connection that had it.
        String afterwards = pool.submit(
                () -> jdbc.sql("select current_setting('lock_timeout')").query(String.class).single())
                .get(15, TimeUnit.SECONDS);
        assertThat(afterwards).isEqualTo("0");
    }

    // --- helpers ---

    /** Takes the payment's row lock in the caller's transaction, like a competing writer. */
    private void lockRow(String id) {
        jdbc.sql("select id from payments where id = :id for update").param("id", UUID.fromString(id))
                .query(UUID.class).single();
    }

    private void awaitABackendWaitingForALock() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            // This poll runs inside the lock-holding transaction, and PostgreSQL freezes its
            // activity snapshot at first use per transaction: drop it so each poll sees fresh data.
            jdbc.sql("select pg_stat_clear_snapshot()").query((rs, n) -> 0).single();
            int waiting = jdbc.sql("select count(*) from pg_stat_activity "
                            + "where wait_event_type = 'Lock' and query ilike '%payments%'")
                    .query(Integer.class).single();
            if (waiting > 0) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
        }
        throw new IllegalStateException("No backend ever waited for the row lock: the webhook did not block");
    }

    private String paymentStatus(String id) {
        return jdbc.sql("select status from payments where id = :id").param("id", UUID.fromString(id))
                .query(String.class).single();
    }

    private String createPendingPayment() throws Exception {
        String body = "{\"orderId\":\"ORD-%s\",\"amount\":%d,\"returnUrl\":\"https://m/r\"}"
                .formatted(UUID.randomUUID(), AMOUNT);
        var res = mvc.perform(post("/api/v1/payments")
                .contentType(MediaType.APPLICATION_JSON).content(body)
                .with(hmac("demo-merchant", DEMO_SECRET, clock.millis(), "idem-" + UUID.randomUUID())))
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(res.getResponse().getContentAsString(), "$.paymentId");
    }
}
