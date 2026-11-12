package com.fluxpay.gateway.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fluxpay.gateway.AbstractIntegrationTest;
import com.fluxpay.gateway.CountingFakeProvider;
import com.fluxpay.gateway.MutableClock;
import com.jayway.jsonpath.JsonPath;

/**
 * Shows the lost-update race at its smallest, with no luck involved. Two transactions read
 * the same PENDING payment (same {@code version}); the second one commits first; the first
 * one then tries to write with a stale version. {@code @Version} turns its UPDATE into
 * {@code ... WHERE id = ? AND version = ?}, which now matches zero rows, so Hibernate
 * refuses the write instead of silently overwriting the winner.
 *
 * <p>The interleaving is forced with latches (never sleeps), so the conflict happens on
 * every run. Each thread owns its transaction through a {@link TransactionTemplate}: a
 * test-level {@code @Transactional} would put both "writers" in one transaction and hide
 * the race.
 */
class OptimisticLockConflictIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    CountingFakeProvider fake;

    @Autowired
    PaymentRepository payments;

    @Autowired
    PaymentStateMachine stateMachine;

    @Autowired
    PlatformTransactionManager txManager;

    @AfterEach
    void reset() {
        clock.reset();
        fake.reset();
    }

    @Test
    void staleWriterIsRejectedAndTheWinnerSurvives() throws Exception {
        UUID id = createPendingPayment();
        TransactionTemplate tx = new TransactionTemplate(txManager);
        CountDownLatch slowHasRead = new CountDownLatch(1);
        CountDownLatch fastHasCommitted = new CountDownLatch(1);
        AtomicBoolean slowFinishedItsCode = new AtomicBoolean();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // Slow writer: reads version 1, then waits until the other writer has committed.
            Future<?> slow = pool.submit(() -> tx.executeWithoutResult(s -> {
                Payment p = payments.loadForSweep(id).orElseThrow();
                slowHasRead.countDown();
                await(fastHasCommitted);
                stateMachine.transition(p, PaymentStatus.CANCELLED, "MERCHANT_CANCEL", TransitionSource.MERCHANT);
                slowHasFinished(slowFinishedItsCode);
            }));
            // Fast writer: reads the same version 1 and commits first.
            Future<?> fast = pool.submit(() -> {
                await(slowHasRead);
                tx.executeWithoutResult(s -> {
                    Payment p = payments.loadForSweep(id).orElseThrow();
                    stateMachine.transition(p, PaymentStatus.SUCCEEDED, "PROVIDER_WEBHOOK", TransitionSource.PROVIDER);
                });
                fastHasCommitted.countDown();
            });

            fast.get(10, TimeUnit.SECONDS);
            Throwable failure = null;
            try {
                slow.get(10, TimeUnit.SECONDS);
            } catch (ExecutionException ex) {
                failure = ex.getCause();
            }

            assertThat(failure).isInstanceOf(ObjectOptimisticLockingFailureException.class);
            // The stale writer's own code ran to the end without an error: the conflict is only
            // detected when the transaction flushes its UPDATE at commit.
            assertThat(slowFinishedItsCode).isTrue();
        } finally {
            pool.shutdownNow();
        }

        // The winner's write stands; the loser's status change AND its audit row rolled back together.
        assertThat(jdbc.sql("select status from payments where id = :id").param("id", id)
                .query(String.class).single()).isEqualTo("SUCCEEDED");
        assertThat(jdbc.sql("select version from payments where id = :id").param("id", id)
                .query(Long.class).single()).isEqualTo(2L);
        assertThat(jdbc.sql("select count(*) from payment_transitions where payment_id = :id "
                        + "and to_status in ('SUCCEEDED','FAILED','EXPIRED','CANCELLED')").param("id", id)
                .query(Integer.class).single()).isEqualTo(1);
    }

    private UUID createPendingPayment() throws Exception {
        String body = "{\"orderId\":\"ORD-%s\",\"amount\":1000000,\"returnUrl\":\"https://m/r\"}"
                .formatted(UUID.randomUUID());
        var res = mvc.perform(post("/api/v1/payments")
                .contentType(MediaType.APPLICATION_JSON).content(body)
                .with(hmac("demo-merchant", DEMO_SECRET, clock.millis(), "idem-" + UUID.randomUUID())))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(JsonPath.read(res.getResponse().getContentAsString(), "$.paymentId"));
    }

    private static void slowHasFinished(AtomicBoolean flag) {
        flag.set(true);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for the other writer");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
