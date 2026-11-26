package com.fluxpay.gateway.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.fluxpay.gateway.AbstractIntegrationTest;
import com.fluxpay.gateway.CountingFakeProvider;
import com.fluxpay.gateway.MutableClock;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * An operator ends a hold. Approve sends the payment to the provider exactly once and makes it
 * payable with a full payment window; reject fails it and frees the order. Either decision can
 * be made once: a payment that is no longer held answers 409.
 */
class ReviewApproveRejectIT extends AbstractIntegrationTest {

    private static final String DEMO = "demo-merchant";
    private static final long HELD_AMOUNT = 30_000_000;

    @Autowired
    MockMvc mvc;

    @Autowired
    CountingFakeProvider fake;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    PaymentSweepService sweeps;

    @Autowired
    ObjectMapper objectMapper;

    @BeforeEach
    void resetCounters() {
        fake.reset();
    }

    @AfterEach
    void reset() {
        fake.reset();
        clock.reset();
    }

    private ResultActions create(String orderId, long amount) throws Exception {
        String body = "{\"orderId\":\"%s\",\"amount\":%d}".formatted(orderId, amount);
        return mvc.perform(post("/api/v1/payments").contentType(MediaType.APPLICATION_JSON).content(body)
                .with(hmac(DEMO, DEMO_SECRET, clock.millis(), "idem-" + UUID.randomUUID())));
    }

    private String hold(String orderId) throws Exception {
        String response = create(orderId, HELD_AMOUNT).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("UNDER_REVIEW"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("paymentId").stringValue();
    }

    private ResultActions approve(String id) throws Exception {
        return mvc.perform(post("/internal/reviews/" + id + "/approve").with(admin()));
    }

    private ResultActions reject(String id) throws Exception {
        return mvc.perform(post("/internal/reviews/" + id + "/reject").with(admin()));
    }

    private String statusOf(String id) {
        return jdbc.sql("select status from payments where id = cast(:id as uuid)").param("id", id)
                .query(String.class).single();
    }

    private List<String> ledger(String id) {
        return jdbc.sql("""
                        select coalesce(from_status, '-') || '>' || to_status || ' ' || reason_code || ' ' || source
                        from payment_transitions where payment_id = cast(:id as uuid) order by created_at, id
                        """)
                .param("id", id).query(String.class).list();
    }

    private static String order() {
        return "ORD-" + UUID.randomUUID();
    }

    @Test
    void approveSendsThePaymentToTheProviderOnceAndMakesItPayable() throws Exception {
        String id = hold(order());
        assertThat(fake.calls()).isZero();

        approve(id).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.payUrl", containsString("/mock/v1/pay/")))
                .andExpect(jsonPath("$.transitions", hasSize(3)));

        assertThat(fake.calls()).isEqualTo(1);
        // All three rows share one test-clock instant, so compare as a set of steps.
        assertThat(ledger(id)).containsExactlyInAnyOrder(
                "CREATED>UNDER_REVIEW HIGH_AMOUNT SYSTEM",
                "UNDER_REVIEW>CREATED REVIEW_APPROVED OPERATOR",
                "CREATED>PENDING PROVIDER_ACCEPTED SYSTEM");
    }

    @Test
    void anApprovedPaymentIsPaidThroughTheNormalWebhook() throws Exception {
        String id = hold(order());
        approve(id).andExpect(status().isOk());

        String json = webhookJson("evt-" + UUID.randomUUID(), "mp_fake_" + id, id, "SUCCESS", HELD_AMOUNT);
        mvc.perform(signedWebhook(json)).andExpect(status().isOk()).andExpect(jsonPath("$.applied").value(true));

        assertThat(statusOf(id)).isEqualTo("SUCCEEDED");
    }

    @Test
    void approveGivesTheCustomerAFullPaymentWindow() throws Exception {
        String id = hold(order());
        clock.advance(Duration.ofMinutes(29)); // one minute before the review deadline

        String response = approve(id).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        Instant expiresAt = Instant.parse(objectMapper.readTree(response).get("expiresAt").stringValue());
        // Payment expiry is 15m from the approval, not what was left of the 30m review window.
        assertThat(expiresAt).isEqualTo(clock.instant().plus(Duration.ofMinutes(15)));

        clock.advance(Duration.ofMinutes(2)); // the old review deadline has now passed
        sweeps.expireOverdue();
        assertThat(statusOf(id)).isEqualTo("PENDING");
    }

    @Test
    void theAbandonedCreateSweepDoesNotFailAPaymentThatIsBeingApproved() throws Exception {
        String id = hold(order());
        clock.advance(Duration.ofMinutes(5)); // held longer than created-timeout (2m)

        // Park the approval inside the provider call: claimed (CREATED), provider not answered yet.
        CountingFakeProvider.CreateGate gate = fake.blockNextCreate();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> approval = pool.submit(() -> approve(id).andReturn().getResponse().getStatus());
            gate.awaitEntered();
            assertThat(statusOf(id)).isEqualTo("CREATED");

            sweeps.failAbandonedCreated();
            assertThat(statusOf(id)).as("status while the provider call is in flight").isEqualTo("CREATED");

            gate.release();
            assertThat(approval.get(15, TimeUnit.SECONDS)).isEqualTo(200);
        } finally {
            gate.release();
            pool.shutdownNow();
        }
        assertThat(statusOf(id)).isEqualTo("PENDING");
    }

    @Test
    void anApprovalThatDiedAfterTheClaimIsStillCleanedUp() throws Exception {
        String id = hold(order());
        clock.advance(Duration.ofMinutes(5)); // approved after a review longer than created-timeout
        // What a crash between claim and provider call leaves behind: CREATED, never handed off.
        jdbc.sql("update payments set status = 'CREATED', updated_at = :now where id = cast(:id as uuid)")
                .param("now", java.time.OffsetDateTime.ofInstant(clock.instant(), java.time.ZoneOffset.UTC))
                .param("id", id).update();

        clock.advance(Duration.ofMinutes(1));
        sweeps.failAbandonedCreated();
        assertThat(statusOf(id)).isEqualTo("CREATED");

        clock.advance(Duration.ofMinutes(2)); // created-timeout (2m) since the claim
        sweeps.failAbandonedCreated();
        assertThat(statusOf(id)).isEqualTo("FAILED");
    }

    @Test
    void rejectFailsThePaymentAndFreesTheOrder() throws Exception {
        String orderId = order();
        String id = hold(orderId);

        reject(id).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.transitions", hasSize(2)));

        assertThat(fake.calls()).isZero();
        assertThat(ledger(id)).contains("UNDER_REVIEW>FAILED RISK_REVIEW_REJECTED OPERATOR");
        create(orderId, 1_000_000).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void aDecisionCanBeMadeOnlyOnce() throws Exception {
        String approved = hold(order());
        approve(approved).andExpect(status().isOk());
        approve(approved).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_ILLEGAL_TRANSITION"));
        // PENDING -> FAILED is a legal move elsewhere; a late reject must still be refused.
        reject(approved).andExpect(status().isConflict());
        assertThat(statusOf(approved)).isEqualTo("PENDING");
        assertThat(fake.calls()).isEqualTo(1);

        String rejected = hold(order());
        reject(rejected).andExpect(status().isOk());
        reject(rejected).andExpect(status().isConflict());
        approve(rejected).andExpect(status().isConflict());
        assertThat(statusOf(rejected)).isEqualTo("FAILED");
        assertThat(fake.calls()).isEqualTo(1);
    }

    @Test
    void aPaymentThatWasNeverHeldCannotBeApprovedOrRejected() throws Exception {
        String response = create(order(), 1_000_000).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(response).get("paymentId").stringValue();

        approve(id).andExpect(status().isConflict());
        reject(id).andExpect(status().isConflict());
        assertThat(statusOf(id)).isEqualTo("PENDING");
    }

    @Test
    void aCancelledOrTimedOutHoldCanNoLongerBeApproved() throws Exception {
        String cancelled = hold(order());
        mvc.perform(post("/api/v1/payments/" + cancelled + "/cancel").with(hmac(DEMO, DEMO_SECRET, clock.millis(), null)))
                .andExpect(status().isOk());
        approve(cancelled).andExpect(status().isConflict());

        String timedOut = hold(order());
        clock.advance(Duration.ofMinutes(31));
        sweeps.expireOverdue();
        approve(timedOut).andExpect(status().isConflict());

        assertThat(statusOf(cancelled)).isEqualTo("CANCELLED");
        assertThat(statusOf(timedOut)).isEqualTo("EXPIRED");
        assertThat(fake.calls()).isZero();
    }

    @Test
    void anUnknownPaymentIs404() throws Exception {
        approve(UUID.randomUUID().toString()).andExpect(status().isNotFound());
        reject(UUID.randomUUID().toString()).andExpect(status().isNotFound());
    }

    @Test
    void aProviderFailureOnApproveFailsThePaymentWith502() throws Exception {
        String orderId = order();
        String id = hold(orderId);
        fake.failNextCalls(true);

        approve(id).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("PROVIDER_UNAVAILABLE"));

        assertThat(statusOf(id)).isEqualTo("FAILED");
        fake.failNextCalls(false);
        create(orderId, 1_000_000).andExpect(status().isCreated());
    }

    @Test
    void theListShowsHeldPaymentsWithTheReasonAndDropsDecidedOnes() throws Exception {
        String orderId = order();
        String id = hold(orderId);

        JsonNode item = find(listing(), id);
        assertThat(item).isNotNull();
        assertThat(item.get("orderId").stringValue()).isEqualTo(orderId);
        assertThat(item.get("amount").longValue()).isEqualTo(HELD_AMOUNT);
        assertThat(item.get("provider").stringValue()).isEqualTo("MOCK");
        assertThat(item.get("holdReason").stringValue()).isEqualTo("HIGH_AMOUNT");
        assertThat(item.get("merchantId").stringValue()).isEqualTo("00000000-0000-0000-0000-000000000001");
        assertThat(Instant.parse(item.get("reviewDeadline").stringValue()))
                .isEqualTo(clock.instant().plus(Duration.ofMinutes(30)));

        approve(id).andExpect(status().isOk());
        assertThat(find(listing(), id)).isNull();
    }

    private JsonNode listing() throws Exception {
        String response = mvc.perform(get("/internal/reviews").with(admin())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private static JsonNode find(JsonNode list, String paymentId) {
        for (JsonNode item : list) {
            if (paymentId.equals(item.get("paymentId").stringValue())) {
                return item;
            }
        }
        return null;
    }
}
