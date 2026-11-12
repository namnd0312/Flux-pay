package com.fluxpay.gateway.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import com.fluxpay.gateway.AbstractIntegrationTest;
import com.fluxpay.gateway.CountingFakeProvider;
import com.fluxpay.gateway.MutableClock;
import com.jayway.jsonpath.JsonPath;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Races the two real writers of a PENDING payment through HTTP: the provider's SUCCESS
 * webhook and the merchant's cancel. Both are released together by a barrier, many times
 * over, so every interleaving the scheduler can produce gets exercised.
 *
 * <p>Whoever commits first wins; that part is luck. What must NOT depend on luck is how the
 * loser is treated. For every payment the test checks:
 * <ul>
 *   <li>no 5xx on either side: losing the race is a business outcome, not a server error;</li>
 *   <li>exactly one terminal row in the audit ledger, and it matches {@code payments.status};</li>
 *   <li>the loser gets its documented answer (cancel: 409; webhook: 200 + ILLEGAL_TRANSITION);</li>
 *   <li>the webhook event is never left in RECEIVED (a stuck event is a lost event, because
 *       the provider's retry would be swallowed as a duplicate).</li>
 * </ul>
 *
 * <p>Runs in the default webhook lock mode (pessimistic). {@link WebhookVsCancelOptimisticModeRaceIT}
 * repeats it with the row lock switched off, where every conflict goes through the retry.
 */
class WebhookVsCancelRaceIT extends AbstractIntegrationTest {

    private static final String DEMO = "demo-merchant";
    private static final long AMOUNT = 1_000_000L;
    private static final int PAYMENTS = 200;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    CountingFakeProvider fake;

    @Autowired
    MeterRegistry meters;

    @AfterEach
    void reset() {
        clock.reset();
        fake.reset();
    }

    @Test
    void loserOfTheRaceGetsABusinessAnswerNeverAServerError() throws Exception {
        double retriesBefore = retriesSoFar();
        double webhookRetriesBefore = webhookRetriesSoFar();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<String> violations = new ArrayList<>();
        int serverErrors = 0;
        int stuckEvents = 0;
        int webhookWins = 0;
        try {
            for (int i = 0; i < PAYMENTS; i++) {
                String id = createPendingPayment();
                String eventId = "evt_" + UUID.randomUUID();
                String json = webhookJson(eventId, "mp_fake_" + id, id, "SUCCESS", AMOUNT);
                CyclicBarrier start = new CyclicBarrier(2);

                Future<MockHttpServletResponse> webhook = pool.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    return mvc.perform(signedWebhook(json)).andReturn().getResponse();
                });
                Future<MockHttpServletResponse> cancel = pool.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    return mvc.perform(post("/api/v1/payments/" + id + "/cancel")
                            .with(hmac(DEMO, DEMO_SECRET, clock.millis(), null))).andReturn().getResponse();
                });
                MockHttpServletResponse webhookRes = webhook.get(15, TimeUnit.SECONDS);
                MockHttpServletResponse cancelRes = cancel.get(15, TimeUnit.SECONDS);

                UUID pid = UUID.fromString(id);
                String finalStatus = jdbc.sql("select status from payments where id = :id").param("id", pid)
                        .query(String.class).single();
                List<String> terminals = jdbc.sql("select to_status from payment_transitions where payment_id = :id "
                                + "and to_status in ('SUCCEEDED','FAILED','EXPIRED','CANCELLED')").param("id", pid)
                        .query(String.class).list();
                String eventStatus = jdbc.sql("select processing_status from webhook_events where event_id = :e")
                        .param("e", eventId).query(String.class).single();

                if (webhookRes.getStatus() >= 500 || cancelRes.getStatus() >= 500) {
                    serverErrors++;
                }
                if ("RECEIVED".equals(eventStatus)) {
                    stuckEvents++;
                }
                if ("SUCCEEDED".equals(finalStatus)) {
                    webhookWins++;
                }
                String problem = check(webhookRes, cancelRes, finalStatus, terminals, eventStatus);
                if (problem != null) {
                    violations.add(id + ": " + problem);
                }
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(violations)
                .as("%d payments raced: webhook won %d, cancel won %d; server errors=%d, events stuck in RECEIVED=%d",
                        PAYMENTS, webhookWins, PAYMENTS - webhookWins, serverErrors, stuckEvents)
                .isEmpty();
        if (expectsVersionConflicts()) {
            // Guard against a green test that never raced: at least one writer must have lost a
            // version check and been retried, otherwise the invariants above proved nothing.
            assertThat(retriesSoFar() - retriesBefore).as("optimistic-lock retries during the races").isPositive();
        } else {
            // Pessimistic mode: the webhook holds the row lock from its read to its commit, so
            // nobody can change the row under it and it can never lose a version check. If this
            // counter moves, the lock mode is not in effect.
            assertThat(webhookRetriesSoFar() - webhookRetriesBefore)
                    .as("webhook-path version conflicts while holding the row lock").isZero();
        }
    }

    private double webhookRetriesSoFar() {
        return meters.find("fluxpay.payment.optimistic.retries").tag("path", "webhook").counters().stream()
                .mapToDouble(Counter::count).sum();
    }

    /**
     * In the default (pessimistic) webhook mode the row lock makes writers queue: the webhook
     * itself never conflicts, and whether cancel does depends on timing. The optimistic-mode
     * subclass turns this on.
     */
    protected boolean expectsVersionConflicts() {
        return false;
    }

    private double retriesSoFar() {
        return meters.find("fluxpay.payment.optimistic.retries").counters().stream().mapToDouble(Counter::count).sum();
    }

    /** Returns a description of the first broken invariant, or null when the outcome is consistent. */
    private static String check(MockHttpServletResponse webhookRes, MockHttpServletResponse cancelRes,
            String finalStatus, List<String> terminals, String eventStatus) throws Exception {
        if (webhookRes.getStatus() != 200) {
            return "webhook answered " + webhookRes.getStatus();
        }
        if (terminals.size() != 1 || !terminals.get(0).equals(finalStatus)) {
            return "terminal audit rows " + terminals + " vs payments.status " + finalStatus;
        }
        boolean applied = JsonPath.read(webhookRes.getContentAsString(), "$.applied");
        String rejectReason = JsonPath.read(webhookRes.getContentAsString(), "$.rejectReason");
        if ("SUCCEEDED".equals(finalStatus)) {
            // Webhook won: cancel must be told the payment is already finished.
            if (cancelRes.getStatus() != 409 || !applied || !"APPLIED".equals(eventStatus)) {
                return "webhook won but cancel=" + cancelRes.getStatus() + " applied=" + applied
                        + " event=" + eventStatus;
            }
        } else if ("CANCELLED".equals(finalStatus)) {
            // Cancel won: the webhook is recorded as rejected, never applied on top.
            if (cancelRes.getStatus() != 200 || applied || !"ILLEGAL_TRANSITION".equals(rejectReason)
                    || !"REJECTED".equals(eventStatus)) {
                return "cancel won but cancel=" + cancelRes.getStatus() + " applied=" + applied
                        + " reject=" + rejectReason + " event=" + eventStatus;
            }
        } else {
            return "unexpected final status " + finalStatus;
        }
        return null;
    }

    private String createPendingPayment() throws Exception {
        String body = "{\"orderId\":\"ORD-%s\",\"amount\":%d,\"returnUrl\":\"https://m/r\"}"
                .formatted(UUID.randomUUID(), AMOUNT);
        var res = mvc.perform(post("/api/v1/payments")
                .contentType(MediaType.APPLICATION_JSON).content(body)
                .with(hmac(DEMO, DEMO_SECRET, clock.millis(), "idem-" + UUID.randomUUID())))
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(res.getResponse().getContentAsString(), "$.paymentId");
    }
}
