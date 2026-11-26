package com.fluxpay.gateway.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
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
import org.springframework.test.web.servlet.MockMvc;

import com.fluxpay.gateway.AbstractIntegrationTest;
import com.fluxpay.gateway.CountingFakeProvider;
import com.fluxpay.gateway.MutableClock;

import tools.jackson.databind.ObjectMapper;

/**
 * Races an approval against everything else that can end a hold: another approval, the
 * merchant's cancel, an operator's reject, and the review timeout. The contenders are released
 * together by a barrier, for many payments, so the scheduler produces both orders.
 *
 * <p>Who wins is luck. What must hold for every payment, whoever wins:
 * <ul>
 *   <li>the provider is called exactly once if the approval won, and not at all otherwise;</li>
 *   <li>no request ends in a 5xx: losing is a business answer (409);</li>
 *   <li>the ledger is one unbroken chain of legal moves that ends in the stored status.</li>
 * </ul>
 * The fake provider throws if it is called inside a transaction, which would show up here as
 * a 5xx.
 */
class ReviewApproveRaceIT extends AbstractIntegrationTest {

    private static final String DEMO = "demo-merchant";
    private static final int ROUNDS = 40;
    private static final int APPROVERS = 10;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    CountingFakeProvider fake;

    @Autowired
    PaymentSweepService sweeps;

    @Autowired
    ObjectMapper objectMapper;

    @AfterEach
    void reset() {
        clock.reset();
        fake.reset();
    }

    @Test
    void manyApprovesOfOnePaymentCallTheProviderOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(APPROVERS);
        List<String> violations = new ArrayList<>();
        try {
            for (int round = 0; round < ROUNDS; round++) {
                String id = hold();
                int callsBefore = fake.calls();
                CyclicBarrier start = new CyclicBarrier(APPROVERS);
                List<Future<Integer>> answers = new ArrayList<>();
                for (int i = 0; i < APPROVERS; i++) {
                    answers.add(pool.submit(() -> {
                        start.await(5, TimeUnit.SECONDS);
                        return approve(id);
                    }));
                }
                int ok = 0;
                int conflicts = 0;
                for (Future<Integer> answer : answers) {
                    int status = answer.get(15, TimeUnit.SECONDS);
                    if (status == 200) {
                        ok++;
                    } else if (status == 409) {
                        conflicts++;
                    }
                }
                int calls = fake.calls() - callsBefore;
                if (ok != 1 || conflicts != APPROVERS - 1 || calls != 1 || !"PENDING".equals(statusOf(id))) {
                    violations.add("%s: %d x 200, %d x 409, %d provider call(s), status %s"
                            .formatted(id, ok, conflicts, calls, statusOf(id)));
                }
                addIfPresent(violations, id, ledgerProblem(id));
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(violations).as("%d payments, %d concurrent approvals each", ROUNDS, APPROVERS).isEmpty();
    }

    @Test
    void approveAgainstMerchantCancel() throws Exception {
        Tally tally = race(id -> () -> mvc.perform(post("/api/v1/payments/" + id + "/cancel")
                        .with(hmac(DEMO, DEMO_SECRET, clock.millis(), null))).andReturn().getResponse().getStatus(),
                (approve, cancel, status) -> {
                    if (approve == 200 && cancel == 409) {
                        return "PENDING".equals(status); // cancel met the short CREATED window
                    }
                    if (approve == 200 && cancel == 200) {
                        return "CANCELLED".equals(status); // cancel came after the approval finished
                    }
                    return approve == 409 && cancel == 200 && "CANCELLED".equals(status);
                });
        assertThat(tally.violations).as(tally.summary("cancel")).isEmpty();
    }

    @Test
    void approveAgainstOperatorReject() throws Exception {
        Tally tally = race(id -> () -> mvc.perform(post("/internal/reviews/" + id + "/reject").with(admin()))
                        .andReturn().getResponse().getStatus(),
                (approve, reject, status) -> (approve == 200 && reject == 409 && "PENDING".equals(status))
                        || (approve == 409 && reject == 200 && "FAILED".equals(status)));
        assertThat(tally.violations).as(tally.summary("reject")).isEmpty();
    }

    @Test
    void approveAgainstTheReviewTimeout() throws Exception {
        // The contender is the sweeper itself; 200 stands for "the sweep ran without throwing".
        Tally tally = race(id -> () -> {
                    sweeps.expireOverdue();
                    return 200;
                },
                (approve, sweep, status) -> (approve == 200 && "PENDING".equals(status))
                        || (approve == 409 && "EXPIRED".equals(status)),
                Duration.ofMinutes(31));
        assertThat(tally.violations).as(tally.summary("timeout")).isEmpty();
    }

    /** Builds the request that competes with the approval of one payment. */
    private interface Contender {
        Callable<Integer> forPayment(String paymentId);
    }

    /** Says whether a pair of answers and the stored status form one of the allowed outcomes. */
    private interface Allowed {
        boolean test(int approveStatus, int contenderStatus, String finalStatus);
    }

    private static final class Tally {
        final List<String> violations = new ArrayList<>();
        int approveWins;

        String summary(String contender) {
            return "%d payments raced approve against %s: approve won %d".formatted(ROUNDS, contender, approveWins);
        }
    }

    private Tally race(Contender contender, Allowed allowed) throws Exception {
        return race(contender, allowed, Duration.ZERO);
    }

    private Tally race(Contender contender, Allowed allowed, Duration ageBeforeRace) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Tally tally = new Tally();
        try {
            for (int round = 0; round < ROUNDS; round++) {
                String id = hold();
                clock.advance(ageBeforeRace);
                int callsBefore = fake.calls();
                CyclicBarrier start = new CyclicBarrier(2);
                Callable<Integer> other = contender.forPayment(id);

                Future<Integer> approval = pool.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    return approve(id);
                });
                Future<Integer> opponent = pool.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    return other.call();
                });
                int approveStatus = approval.get(15, TimeUnit.SECONDS);
                int otherStatus = opponent.get(15, TimeUnit.SECONDS);
                String status = statusOf(id);
                int calls = fake.calls() - callsBefore;

                if (approveStatus == 200) {
                    tally.approveWins++;
                }
                if (!allowed.test(approveStatus, otherStatus, status)) {
                    tally.violations.add("%s: approve %d, contender %d, status %s"
                            .formatted(id, approveStatus, otherStatus, status));
                }
                if (calls != (approveStatus == 200 ? 1 : 0)) {
                    tally.violations.add("%s: approve %d but %d provider call(s)".formatted(id, approveStatus, calls));
                }
                addIfPresent(tally.violations, id, ledgerProblem(id));
            }
        } finally {
            pool.shutdownNow();
        }
        return tally;
    }

    private String hold() throws Exception {
        String body = "{\"orderId\":\"ORD-%s\",\"amount\":30000000}".formatted(UUID.randomUUID());
        String response = mvc.perform(post("/api/v1/payments").contentType(MediaType.APPLICATION_JSON).content(body)
                        .with(hmac(DEMO, DEMO_SECRET, clock.millis(), "idem-" + UUID.randomUUID())))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("paymentId").stringValue();
    }

    private int approve(String id) throws Exception {
        return mvc.perform(post("/internal/reviews/" + id + "/approve").with(admin())).andReturn().getResponse()
                .getStatus();
    }

    private String statusOf(String id) {
        return jdbc.sql("select status from payments where id = cast(:id as uuid)").param("id", id)
                .query(String.class).single();
    }

    /**
     * Checks the payment's ledger. Under the test clock the rows of one payment can share a
     * timestamp, so they cannot simply be sorted; instead the rows must be arrangeable into one
     * chain that starts at CREATED, uses every row once, and ends at the stored status. A
     * payment that was held and approved leaves CREATED twice, hence the search. Returns null
     * when every row is a legal move and such a chain exists, otherwise what is wrong.
     */
    private String ledgerProblem(String id) {
        List<String[]> rows = jdbc.sql("""
                        select from_status, to_status from payment_transitions where payment_id = cast(:id as uuid)
                        """)
                .param("id", id)
                .query((rs, n) -> new String[] {rs.getString("from_status"), rs.getString("to_status")}).list();
        for (String[] row : rows) {
            if (!PaymentStateMachine.isAllowed(PaymentStatus.valueOf(row[0]), PaymentStatus.valueOf(row[1]))) {
                return "ledger holds the illegal move " + row[0] + " -> " + row[1];
            }
        }
        String status = statusOf(id);
        if (chains("CREATED", status, new ArrayList<>(rows))) {
            return null;
        }
        return "ledger rows " + rows.stream().map(r -> r[0] + ">" + r[1]).toList()
                + " do not form one chain from CREATED to " + status;
    }

    private static boolean chains(String at, String end, List<String[]> unused) {
        if (unused.isEmpty()) {
            return at.equals(end);
        }
        for (String[] row : List.copyOf(unused)) {
            if (at.equals(row[0])) {
                unused.remove(row);
                if (chains(row[1], end, unused)) {
                    return true;
                }
                unused.add(row);
            }
        }
        return false;
    }

    private static void addIfPresent(List<String> violations, String id, String problem) {
        if (problem != null) {
            violations.add(id + ": " + problem);
        }
    }
}
